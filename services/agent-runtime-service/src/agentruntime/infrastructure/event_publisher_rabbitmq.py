"""SPEC-ARO-025 08-transaction-and-outbox §"Outbox Publisher": the real
EventPublisherPort adapter — replaces LoggingEventPublisherAdapter (SPEC-ARO-003's own
placeholder, whose docstring named this spec directly: "Real RabbitMQ wiring is
phase-07"). 06-event-contracts §"Envelope" is the wire format every consumer expects:

    {"eventId": ..., "eventType": ..., "aggregateId": ..., "ticketId": ...,
     "correlationId": ..., "causationId": ..., "occurredAt": ..., "payload": {}}

OutboxRecord carries each of those fields separately (SPEC-ARO-002's own schema
baseline) plus its own inner `payload` already JSON-encoded by whichever application
service appended it — this adapter's only real job is assembling the envelope around
that payload and handing the result to a real broker, exactly the seam
DispatchOutboxEventsService already treats as opaque ("Returns True on success. Must
never raise for an ordinary delivery failure").

pika (blocking), not an async client: this whole codebase is synchronous end to end
(every application service and FastAPI route is `def`, never `async def`); mirrors the
technology-baseline's "RabbitMQ Async Client" as a description of the broker's own
decoupled messaging model, not a mandate for Python-level asyncio.
"""

from __future__ import annotations

import json
import logging
import threading

import pika
import pika.exceptions

from agentruntime.application.records import OutboxRecord
from agentruntime.settings import Settings

logger = logging.getLogger(__name__)

_CONTENT_TYPE = "application/json"


class RabbitMqEventPublisherAdapter:
    """Lazily opens a single blocking connection/channel on first use and reuses it
    across publish() calls (DispatchOutboxEventsService may call this once per record in
    a batch) — reconnects on the next call after any failure rather than trying to
    recover mid-call, keeping the retry/backoff decision entirely in
    DispatchOutboxEventsService's own hands (a returned False there is a normal, expected
    outcome, not a bug).
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._connection: pika.BlockingConnection | None = None
        self._channel: pika.adapters.blocking_connection.BlockingChannel | None = None
        self._lock = threading.Lock()

    def publish(self, record: OutboxRecord) -> bool:
        with self._lock:
            try:
                channel = self._ensure_channel()
                channel.basic_publish(
                    exchange=self._settings.rabbitmq_exchange,
                    routing_key=record.event_type,
                    body=self._to_envelope_json(record),
                    properties=pika.BasicProperties(
                        message_id=str(record.outbox_id), content_type=_CONTENT_TYPE, delivery_mode=2,
                        correlation_id=str(record.correlation_id), type=record.event_type,
                    ),
                )
                return True
            except (pika.exceptions.AMQPError, OSError) as exc:
                logger.warning("rabbitmq publish failed outbox_id=%s event_type=%s error=%s", record.outbox_id, record.event_type, exc)
                self._reset_connection()
                return False

    def _to_envelope_json(self, record: OutboxRecord) -> bytes:
        envelope = {
            "eventId": str(record.outbox_id),
            "eventType": record.event_type,
            "aggregateId": str(record.workflow_instance_id),
            "ticketId": str(record.ticket_id),
            "correlationId": str(record.correlation_id),
            "causationId": str(record.causation_id),
            "occurredAt": record.occurred_at.isoformat(),
            "payload": json.loads(record.payload),
        }
        return json.dumps(envelope).encode("utf-8")

    def _ensure_channel(self) -> pika.adapters.blocking_connection.BlockingChannel:
        if self._connection is None or self._connection.is_closed:
            credentials = pika.PlainCredentials(self._settings.rabbitmq_username, self._settings.rabbitmq_password)
            parameters = pika.ConnectionParameters(
                host=self._settings.rabbitmq_host, port=self._settings.rabbitmq_port,
                virtual_host=self._settings.rabbitmq_vhost, credentials=credentials,
            )
            self._connection = pika.BlockingConnection(parameters)
            self._channel = self._connection.channel()
            self._channel.exchange_declare(exchange=self._settings.rabbitmq_exchange, exchange_type="topic", durable=True)
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
