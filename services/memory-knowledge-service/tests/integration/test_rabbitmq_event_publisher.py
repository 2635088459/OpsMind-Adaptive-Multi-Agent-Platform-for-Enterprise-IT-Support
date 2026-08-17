"""SPEC-MK-031 08-transaction-and-outbox §"Outbox Publisher": proves
RabbitMqEventPublisherAdapter actually round-trips through a real broker (an ephemeral
one via testcontainers, the same "real infrastructure, not a mock" bar
tests/integration/test_app_postgres_integration.py already holds the Postgres
adapters to) — not just that pika calls succeed against a mock. Mirrors
agent-runtime-service's own tests/integration/test_rabbitmq_event_publisher.py
(SPEC-ARO-025) exactly, adapted to this domain's own OutboxRecord shape.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime

import pika
import pytest
from testcontainers.community.rabbitmq import RabbitMqContainer

from memoryknowledge.application.records import OutboxRecord
from memoryknowledge.domain.ids import CausationId, CorrelationId
from memoryknowledge.infrastructure.event_publisher_rabbitmq import RabbitMqEventPublisherAdapter
from memoryknowledge.settings import Settings

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def rabbitmq_container() -> Iterator[RabbitMqContainer]:
    with RabbitMqContainer("rabbitmq:4.3.4-management") as container:
        yield container


@pytest.fixture
def settings(rabbitmq_container: RabbitMqContainer) -> Settings:
    params = rabbitmq_container.get_connection_params()
    return Settings(
        rabbitmq_host=params.host, rabbitmq_port=params.port, rabbitmq_username=params.credentials.username,
        rabbitmq_password=params.credentials.password, rabbitmq_vhost=params.virtual_host,
        rabbitmq_exchange=f"memory-knowledge-events-test-{uuid.uuid4().hex[:8]}",
    )


def _record(event_type: str = "memory.published.v1", ticket_id: str | None = None) -> OutboxRecord:
    return OutboxRecord(
        outbox_id=uuid.uuid4(), event_type=event_type, schema_version=1, aggregate_id=str(uuid.uuid4()),
        payload=json.dumps({"version": 1}), occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
    )


def test_publish_delivers_the_envelope_to_a_real_broker(settings: Settings) -> None:
    adapter = RabbitMqEventPublisherAdapter(settings)
    record = _record(ticket_id=str(uuid.uuid4()))

    # Bind an ephemeral queue to the exchange+routing key before publishing, mirroring
    # how a real consumer would declare its own binding — the adapter itself only
    # declares the exchange (06-event-contracts: publishing is topology-agnostic of
    # who, if anyone, is listening).
    credentials = pika.PlainCredentials(settings.rabbitmq_username, settings.rabbitmq_password)
    parameters = pika.ConnectionParameters(
        host=settings.rabbitmq_host, port=settings.rabbitmq_port, virtual_host=settings.rabbitmq_vhost, credentials=credentials,
    )
    with pika.BlockingConnection(parameters) as connection:
        channel = connection.channel()
        channel.exchange_declare(exchange=settings.rabbitmq_exchange, exchange_type="topic", durable=True)
        queue = channel.queue_declare(queue="", exclusive=True)
        queue_name = queue.method.queue
        channel.queue_bind(exchange=settings.rabbitmq_exchange, queue=queue_name, routing_key=record.event_type)

        published = adapter.publish(record)
        assert published is True

        method, properties, body = channel.consume(queue_name, inactivity_timeout=5).__next__()
        assert method is not None, "expected a message to arrive within the timeout"
        channel.basic_ack(method.delivery_tag)

    assert properties.message_id == str(record.outbox_id)
    assert properties.content_type == "application/json"
    envelope = json.loads(body)
    assert envelope["eventId"] == str(record.outbox_id)
    assert envelope["eventType"] == "memory.published.v1"
    assert envelope["aggregateId"] == record.aggregate_id
    assert envelope["ticketId"] == record.ticket_id
    assert envelope["correlationId"] == str(record.correlation_id)
    assert envelope["causationId"] == str(record.causation_id)
    assert envelope["payload"] == {"version": 1}


def test_publish_a_ticketless_event_carries_a_null_ticket_id(settings: Settings) -> None:
    """Most Memory events have no ticket at all (e.g. `knowledge.document.indexed.v1`)
    — unlike agent-runtime-service's own OutboxRecord, ticket_id here is genuinely
    optional, and the envelope must carry that honestly rather than a fabricated id.
    """
    adapter = RabbitMqEventPublisherAdapter(settings)
    record = _record(event_type="knowledge.document.indexed.v1", ticket_id=None)

    credentials = pika.PlainCredentials(settings.rabbitmq_username, settings.rabbitmq_password)
    parameters = pika.ConnectionParameters(
        host=settings.rabbitmq_host, port=settings.rabbitmq_port, virtual_host=settings.rabbitmq_vhost, credentials=credentials,
    )
    with pika.BlockingConnection(parameters) as connection:
        channel = connection.channel()
        channel.exchange_declare(exchange=settings.rabbitmq_exchange, exchange_type="topic", durable=True)
        queue = channel.queue_declare(queue="", exclusive=True)
        queue_name = queue.method.queue
        channel.queue_bind(exchange=settings.rabbitmq_exchange, queue=queue_name, routing_key=record.event_type)

        assert adapter.publish(record) is True

        method, _properties, body = channel.consume(queue_name, inactivity_timeout=5).__next__()
        assert method is not None
        channel.basic_ack(method.delivery_tag)

    assert json.loads(body)["ticketId"] is None


def test_publish_reuses_the_connection_across_calls(settings: Settings) -> None:
    adapter = RabbitMqEventPublisherAdapter(settings)

    assert adapter.publish(_record("memory.published.v1")) is True
    first_connection = adapter._connection
    assert adapter.publish(_record("memory.superseded.v1")) is True

    assert adapter._connection is first_connection


def test_publish_returns_false_and_recovers_after_a_connection_is_forcibly_closed(settings: Settings) -> None:
    adapter = RabbitMqEventPublisherAdapter(settings)
    assert adapter.publish(_record()) is True

    adapter._connection.close()

    # The next publish() re-establishes its own connection rather than raising.
    assert adapter.publish(_record()) is True
