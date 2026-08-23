"""SPEC-TG-003 08-transaction-and-outbox §"Outbox Publisher": the real
EventBusPort adapter — replaces LoggingEventBusAdapter as the deployed default
once ``event_publisher_adapter="rabbitmq"`` is set. Mirrors memory-knowledge-
service's own RabbitMqEventPublisherAdapter (SPEC-MK-031) and agent-runtime-
service's own RabbitMqEventPublisherAdapter (SPEC-ARO-025) line for line,
adapted to this domain's own OutboxRecord shape (``aggregate_type``/
``aggregate_id`` pair, no separate ``causation_id``/``ticket_id`` fields).

technology-baseline §8 "Event envelope" is the wire format every consumer
expects (eventId/eventType/eventVersion/occurredAt/producer/correlationId/
aggregateId/payload).

pika (blocking), not an async client: this whole codebase is synchronous end
to end (every application service and FastAPI route is ``def``, never
``async def``) — mirrors the technology-baseline's "RabbitMQ Async Client" as a
description of the broker's own decoupled messaging model, not a mandate for
Python-level asyncio, the same reasoning agent-runtime-service's and memory-
knowledge-service's own module docstrings already give.
"""

from __future__ import annotations

import json
import logging
import threading

import pika
import pika.exceptions

from tool_gateway.domain.records import OutboxRecord
from tool_gateway.settings import Settings

logger = logging.getLogger(__name__)

_CONTENT_TYPE = "application/json"


class RabbitMqEventBusAdapter:
    """Lazily opens a single blocking connection/channel on first use and
    reuses it across publish() calls (PublishOutboxService may call this once
    per record in a batch) — reconnects on the next call after any failure
    rather than trying to recover mid-call, keeping the retry/backoff decision
    entirely in PublishOutboxService's own hands (a returned False here is a
    normal, expected outcome, not a bug).
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
                        correlation_id=record.correlation_id, type=record.event_type,
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
            "eventVersion": record.event_version,
            "occurredAt": record.occurred_at.isoformat(),
            "producer": self._settings.tool_gateway_service_name,
            "correlationId": record.correlation_id,
            "aggregateId": record.aggregate_id,
            "payload": record.payload,
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
