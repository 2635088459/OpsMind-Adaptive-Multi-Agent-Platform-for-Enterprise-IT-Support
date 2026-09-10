"""The real RabbitMQ-backed ``EventPublisherPort`` adapter.

Deferred through every prior spec (see ``infrastructure.event_publisher`` — the
``LoggingEventPublisherAdapter`` placeholder — and this module's own earlier
"still not built here" docstring). SPEC-XREL-001 needs it: ``improvement.promoted.v1``
has to actually reach ``opsmind.events`` for the event-relay sidecar to fan it out to
agent-runtime's ``/events/improvement-promoted`` seam. Until now the event was only
logged.

Mirrors agent-runtime-service's own ``RabbitMqEventPublisherAdapter`` exactly:
``pika`` blocking client (no asyncio anywhere in this platform), one lazily-opened
connection reused across ``publish()`` calls, reconnect on the *next* call after any
failure rather than mid-call — the retry/backoff decision stays entirely in
``DispatchOutboxEventsService``'s hands (a returned ``False`` there is a normal,
expected outcome, never an exception).

06-event-contracts §"Envelope" is the wire format every consumer expects:

    {"eventId": ..., "eventType": ..., "aggregateId": ..., "schemaVersion": ...,
     "producer": ..., "correlationId": ..., "occurredAt": ..., "payload": {}}

``evaluation-improvement``'s ``OutboxRecord`` carries no ``ticketId``/``causationId``
(its events are platform-config facts, not ticket-scoped) — the envelope simply omits
them, which is what agent-runtime's ``ImprovementPromotedEventRequest`` model-validator
already tolerates (it reads ``eventId``/``eventType``/``producer``/``occurredAt`` plus
the flattened ``payload``).
"""

from __future__ import annotations

import logging
import threading

import pika
import pika.exceptions

from evaluationimprovement.application.records import OutboxRecord

logger = logging.getLogger(__name__)

_CONTENT_TYPE = "application/json"


class RabbitMqEventPublisherAdapter:
    def __init__(
        self,
        *,
        host: str,
        port: int,
        username: str,
        password: str,
        vhost: str,
        exchange: str,
    ) -> None:
        self._host = host
        self._port = port
        self._username = username
        self._password = password
        self._vhost = vhost
        self._exchange = exchange
        self._connection: pika.BlockingConnection | None = None
        self._channel: pika.adapters.blocking_connection.BlockingChannel | None = None
        self._lock = threading.Lock()

    def publish(self, record: OutboxRecord) -> bool:
        with self._lock:
            try:
                channel = self._ensure_channel()
                channel.basic_publish(
                    exchange=self._exchange,
                    routing_key=record.event_type,
                    body=self._body(record),
                    properties=pika.BasicProperties(
                        message_id=str(record.outbox_id), content_type=_CONTENT_TYPE, delivery_mode=2,
                        correlation_id=str(record.correlation_id), type=record.event_type,
                    ),
                )
                return True
            except (pika.exceptions.AMQPError, OSError) as exc:
                logger.warning(
                    "rabbitmq publish failed outbox_id=%s event_type=%s error=%s",
                    record.outbox_id, record.event_type, exc,
                )
                self._reset_connection()
                return False

    def _body(self, record: OutboxRecord) -> bytes:
        # `record.payload` is ALREADY the full 06-event-contracts envelope
        # (application.outbox_codec.build_outbox_record assembles
        # {eventId,eventType,eventVersion,occurredAt,producer,traceId,correlationId,
        # runId,candidateId,payload:{...}} into it) — unlike agent-runtime-service,
        # whose OutboxRecord.payload is only the inner business payload and whose
        # publisher wraps the envelope itself. Re-wrapping here would double-nest and
        # break the downstream `payload`-flattening in agent-runtime's
        # ImprovementPromotedEventRequest validator. So: publish it verbatim.
        return (record.payload or "{}").encode("utf-8")

    def _ensure_channel(self) -> pika.adapters.blocking_connection.BlockingChannel:
        if self._connection is None or self._connection.is_closed:
            credentials = pika.PlainCredentials(self._username, self._password)
            parameters = pika.ConnectionParameters(
                host=self._host, port=self._port, virtual_host=self._vhost, credentials=credentials,
            )
            self._connection = pika.BlockingConnection(parameters)
            self._channel = self._connection.channel()
            self._channel.exchange_declare(exchange=self._exchange, exchange_type="topic", durable=True)
        assert self._channel is not None
        return self._channel

    def _reset_connection(self) -> None:
        try:
            if self._connection is not None and not self._connection.is_closed:
                self._connection.close()
        except pika.exceptions.AMQPError:
            pass
        self._connection = None
        self._channel = None
