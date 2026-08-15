from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from agentruntime.application.commands import ConsumeTicketCreatedCommand
from agentruntime.application.exceptions import AutomationNotAllowedException
from agentruntime.application.records import TicketSnapshot
from agentruntime.application.services.consume_ticket_created import CONSUMER_NAME, ConsumeTicketCreatedService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.domain.ids import CausationId, CorrelationId, TicketCycleId, TicketId
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryProcessedEventRepository,
    InMemoryWorkflowInstanceRepository,
)
from agentruntime.infrastructure.workflow_definition_catalog import StaticWorkflowDefinitionCatalogAdapter
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


class StubTicketSnapshotPort:
    """Test double letting each test control what "confirm automation can start"
    (04-use-cases UC-01 step 3) observes, without a real Ticket Workflow integration.
    """

    def __init__(self, snapshot: TicketSnapshot | None = None) -> None:
        self._snapshot = snapshot

    def find_snapshot(self, ticket_id: TicketId) -> TicketSnapshot | None:
        return self._snapshot


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    outbox_repository = InMemoryOutboxRepository()
    processed_event_repository = InMemoryProcessedEventRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    start_workflow_service = StartWorkflowService(
        workflow_instance_repository, checkpoint_repository, outbox_repository, command_idempotency_repository, clock,
        coordinate_agent_tasks_service, telemetry, audit_recorder,
    )

    def build(snapshot: TicketSnapshot | None = None) -> ConsumeTicketCreatedService:
        return ConsumeTicketCreatedService(
            processed_event_repository, StubTicketSnapshotPort(snapshot), StaticWorkflowDefinitionCatalogAdapter(),
            start_workflow_service, clock, telemetry,
        )

    return build, workflow_instance_repository, checkpoint_repository, agent_task_repository, outbox_repository, processed_event_repository


def _command(event_id: str = "evt-1", ticket_id: uuid.UUID | None = None, ticket_cycle_id: uuid.UUID | None = None) -> ConsumeTicketCreatedCommand:
    return ConsumeTicketCreatedCommand(
        event_id=event_id, event_type="ticket.created.v1", producer="ticket-workflow-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(),
        ticket_id=TicketId(ticket_id or uuid.uuid4()), ticket_cycle_id=TicketCycleId(ticket_cycle_id or uuid.uuid4()),
        priority="HIGH", category="network_outage", created_by="ticket-workflow-service", occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
    )


def test_consuming_ticket_created_starts_a_workflow_and_writes_a_started_checkpoint(wiring) -> None:
    build, workflow_instance_repository, checkpoint_repository, agent_task_repository, outbox_repository, processed_event_repository = wiring
    service = build()

    applied = service.consume(_command())

    assert applied is True
    assert processed_event_repository.is_processed("evt-1", CONSUMER_NAME)
    assert len(outbox_repository.recorded()) == 1
    assert outbox_repository.recorded()[0].event_type == "workflow.started.v1"

    [outbox_record] = outbox_repository.recorded()
    checkpoints = checkpoint_repository.find_by_workflow_instance_id(outbox_record.workflow_instance_id)
    assert [c.type.name for c in checkpoints] == ["STARTED"]
    tasks = agent_task_repository.find_by_workflow_instance_id(outbox_record.workflow_instance_id)
    assert [task.task_key for task in tasks] == ["collect"]


def test_an_event_id_already_marked_processed_by_a_different_consumer_is_still_consumed_here(wiring) -> None:
    """SPEC-ARO-013 09-concurrency-and-idempotency §"消费事件幂等": dedup is keyed by
    (event_id, consumer_name) — a coincidental event_id collision with some other logical
    consumer (e.g. ConsumeRuntimeEventService) must not block this one from processing its
    own event.
    """
    build, *_rest, outbox_repository, processed_event_repository = wiring
    processed_event_repository.mark_processed("evt-shared", "some_other_consumer", datetime(2026, 1, 1, tzinfo=UTC))
    service = build()

    applied = service.consume(_command("evt-shared"))

    assert applied is True
    assert len(outbox_repository.recorded()) == 1


def test_a_duplicate_event_id_is_not_reprocessed(wiring) -> None:
    build, *_rest, outbox_repository, processed_event_repository = wiring
    service = build()
    command = _command("evt-dup")
    service.consume(command)

    applied_again = service.consume(command)

    assert applied_again is False
    assert len(outbox_repository.recorded()) == 1


def test_a_retried_event_under_a_different_event_id_for_the_same_ticket_cycle_hits_the_start_idempotency_guard(wiring) -> None:
    """06-event-contracts: "Idempotency key: eventId or ticketId + ticketCycleId +
    workflowType" — a different eventId does not defeat the composite-key guard inside
    StartWorkflowService, so no second Workflow Instance is created.
    """
    build, *_rest, outbox_repository, _ = wiring
    service = build()
    ticket_id = uuid.uuid4()
    ticket_cycle_id = uuid.uuid4()
    service.consume(_command("evt-1", ticket_id, ticket_cycle_id))

    applied_again = service.consume(_command("evt-2", ticket_id, ticket_cycle_id))

    assert applied_again is True
    assert len(outbox_repository.recorded()) == 1


def test_a_terminal_ticket_status_blocks_automation_and_still_marks_the_event_processed(wiring) -> None:
    build, _, _, _, outbox_repository, processed_event_repository = wiring
    snapshot = TicketSnapshot(ticket_id=TicketId(uuid.uuid4()), ticket_status="CLOSED", ticket_version=1, observed_at=datetime(2026, 1, 1, tzinfo=UTC))
    service = build(snapshot)

    with pytest.raises(AutomationNotAllowedException):
        service.consume(_command("evt-blocked"))

    assert processed_event_repository.is_processed("evt-blocked", CONSUMER_NAME)
    assert len(outbox_repository.recorded()) == 0


def test_no_snapshot_available_does_not_block_automation(wiring) -> None:
    """NoOpTicketSnapshotPort (and this stub with snapshot=None) is the real-run default
    until a later spec wires the Ticket Workflow query adapter — a start must not be
    blocked just because that integration doesn't exist yet.
    """
    build, *_rest, outbox_repository, _ = wiring
    service = build(None)

    applied = service.consume(_command())

    assert applied is True
    assert len(outbox_repository.recorded()) == 1
