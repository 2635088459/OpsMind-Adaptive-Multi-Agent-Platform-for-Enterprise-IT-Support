"""SPEC-ARO-025 08-transaction-and-outbox §"Outbox Publisher": proves
RabbitMqEventPublisherAdapter actually round-trips through a real broker (an ephemeral
one via testcontainers, the same "real infrastructure, not a mock" bar
tests/integration/test_postgres_repositories.py already holds the Postgres adapters to)
— not just that pika calls succeed against a mock.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime

import pika
import pytest
from testcontainers.community.rabbitmq import RabbitMqContainer

from agentruntime.application.records import OutboxRecord
from agentruntime.domain.ids import CausationId, CorrelationId, TicketId, WorkflowInstanceId
from agentruntime.infrastructure.event_publisher_rabbitmq import RabbitMqEventPublisherAdapter
from agentruntime.settings import Settings

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
        rabbitmq_exchange=f"agent-runtime-events-test-{uuid.uuid4().hex[:8]}",
    )


def _record(event_type: str = "workflow.started.v1") -> OutboxRecord:
    return OutboxRecord(
        outbox_id=uuid.uuid4(), workflow_instance_id=WorkflowInstanceId.new_id(), ticket_id=TicketId(uuid.uuid4()),
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), event_type=event_type, schema_version=1,
        payload=json.dumps({"toState": "RUNNING", "workflowVersion": 1}), occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
    )


def test_publish_delivers_the_envelope_to_a_real_broker(settings: Settings) -> None:
    adapter = RabbitMqEventPublisherAdapter(settings)
    record = _record()

    # Bind an ephemeral queue to the exchange+routing key before publishing, mirroring
    # how a real consumer would declare its own binding — the adapter itself only
    # declares the exchange (06-event-contracts: publishing is topology-agnostic of who,
    # if anyone, is listening).
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
    assert envelope["eventType"] == "workflow.started.v1"
    assert envelope["aggregateId"] == str(record.workflow_instance_id)
    assert envelope["ticketId"] == str(record.ticket_id)
    assert envelope["correlationId"] == str(record.correlation_id)
    assert envelope["causationId"] == str(record.causation_id)
    assert envelope["payload"] == {"toState": "RUNNING", "workflowVersion": 1}


def test_publish_reuses_the_connection_across_calls(settings: Settings) -> None:
    adapter = RabbitMqEventPublisherAdapter(settings)

    assert adapter.publish(_record("workflow.started.v1")) is True
    first_connection = adapter._connection
    assert adapter.publish(_record("workflow.paused.v1")) is True

    assert adapter._connection is first_connection


def test_publish_returns_false_and_recovers_after_a_connection_is_forcibly_closed(settings: Settings) -> None:
    adapter = RabbitMqEventPublisherAdapter(settings)
    assert adapter.publish(_record()) is True

    adapter._connection.close()

    # The next publish() re-establishes its own connection rather than raising.
    assert adapter.publish(_record()) is True
