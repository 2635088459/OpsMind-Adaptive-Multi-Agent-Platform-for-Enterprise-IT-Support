"""SPEC-TG-003 08-transaction-and-outbox §"Outbox Publisher": proves
RabbitMqEventBusAdapter actually round-trips through a real broker (an
ephemeral one via testcontainers, the same "real infrastructure, not a mock"
bar test_app_postgres_integration.py already holds the Postgres adapters to) —
not just that pika calls succeed against a mock. Mirrors memory-knowledge-
service's own tests/integration/test_rabbitmq_event_publisher.py (SPEC-MK-031)
exactly, adapted to this domain's own OutboxRecord shape.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime

import pika
import pytest
from testcontainers.community.rabbitmq import RabbitMqContainer

from tool_gateway.adapters.events.rabbitmq_publisher import RabbitMqEventBusAdapter
from tool_gateway.domain.records import OutboxRecord
from tool_gateway.settings import Settings

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
        rabbitmq_exchange=f"tool-integration-gateway-events-test-{uuid.uuid4().hex[:8]}",
    )


def _record(event_type: str = "tool.request.accepted.v1") -> OutboxRecord:
    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(uuid.uuid4()), event_type=event_type,
        event_version="1.0", payload={"capabilityName": "kubernetes.getPodLogs"}, occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
        correlation_id=str(uuid.uuid4()),
    )


def _consume_one(settings: Settings, record: OutboxRecord, adapter: RabbitMqEventBusAdapter) -> dict:
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

        method, properties, body = channel.consume(queue_name, inactivity_timeout=5).__next__()
        assert method is not None, "expected a message to arrive within the timeout"
        channel.basic_ack(method.delivery_tag)
        assert properties.message_id == str(record.outbox_id)
        assert properties.content_type == "application/json"
        return json.loads(body)


def test_publish_delivers_the_envelope_to_a_real_broker(settings: Settings) -> None:
    adapter = RabbitMqEventBusAdapter(settings)
    record = _record()

    envelope = _consume_one(settings, record, adapter)

    assert envelope["eventId"] == str(record.outbox_id)
    assert envelope["eventType"] == "tool.request.accepted.v1"
    assert envelope["eventVersion"] == "1.0"
    assert envelope["aggregateId"] == record.aggregate_id
    assert envelope["correlationId"] == record.correlation_id
    assert envelope["producer"] == "tool-integration-gateway"
    assert envelope["payload"] == {"capabilityName": "kubernetes.getPodLogs"}


def test_publish_reuses_the_connection_across_calls(settings: Settings) -> None:
    adapter = RabbitMqEventBusAdapter(settings)

    assert adapter.publish(_record("tool.request.accepted.v1")) is True
    first_connection = adapter._connection
    assert adapter.publish(_record("tool.completed.v1")) is True

    assert adapter._connection is first_connection


def test_publish_returns_false_and_recovers_after_a_connection_is_forcibly_closed(settings: Settings) -> None:
    adapter = RabbitMqEventBusAdapter(settings)
    assert adapter.publish(_record()) is True

    adapter._connection.close()

    # The next publish() re-establishes its own connection rather than raising.
    assert adapter.publish(_record()) is True
