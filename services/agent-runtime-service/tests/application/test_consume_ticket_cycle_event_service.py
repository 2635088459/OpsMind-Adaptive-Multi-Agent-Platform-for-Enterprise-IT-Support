from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from agentruntime.application.commands import ConsumeTicketCancelledCommand, ConsumeTicketReopenedCommand
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.cancel_workflow import CancelWorkflowService
from agentruntime.application.services.consume_ticket_cycle_event import CONSUMER_NAME, ConsumeTicketCycleEventService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.ids import (
    CausationId,
    CorrelationId,
    DefinitionVersion,
    TicketCycleId,
    TicketId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryProcessedEventRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    processed_event_repository = InMemoryProcessedEventRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    clock = FakeClock()
    _telemetry, audit_recorder = build_telemetry_collaborators(clock)
    cancel_workflow_service = CancelWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        audit_recorder,
    )
    service = ConsumeTicketCycleEventService(processed_event_repository, workflow_instance_repository, clock, cancel_workflow_service)
    return service, workflow_instance_repository, processed_event_repository, outbox_repository, clock


def _seed_workflow(
    workflow_instance_repository, clock, ticket_id: TicketId, ticket_cycle_id: TicketCycleId,
    state: WorkflowState = WorkflowState.RUNNING, workflow_type: WorkflowType = WorkflowType("TICKET_TRIAGE"),
) -> WorkflowInstanceId:
    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id, workflow_type=workflow_type,
        definition_id=WorkflowDefinitionId("triage-v1"), definition_version=DefinitionVersion(1), state=state,
        workflow_version=1, pause_generation=0, created_at=now, updated_at=now,
    ))
    return workflow_instance_id


def _cancelled_command(ticket_id: TicketId, ticket_cycle_id: TicketCycleId, event_id: str = "evt-cancel-1") -> ConsumeTicketCancelledCommand:
    return ConsumeTicketCancelledCommand(
        event_id=event_id, event_type="ticket.cancelled.v1", producer="ticket-workflow-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        ticket_cycle_id=ticket_cycle_id, cancel_reason_code="NO_LONGER_NEEDED", occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
    )


def _reopened_command(
    ticket_id: TicketId, previous_ticket_cycle_id: TicketCycleId, new_ticket_cycle_id: TicketCycleId | None = None,
    event_id: str = "evt-reopen-1",
) -> ConsumeTicketReopenedCommand:
    return ConsumeTicketReopenedCommand(
        event_id=event_id, event_type="ticket.reopened.v1", producer="ticket-workflow-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        previous_ticket_cycle_id=previous_ticket_cycle_id, new_ticket_cycle_id=new_ticket_cycle_id or TicketCycleId(uuid.uuid4()),
        reason_code="ISSUE_RECURRED", occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
    )


def test_ticket_cancelled_cancels_the_active_workflow_for_that_ticket_cycle(wiring) -> None:
    service, workflow_instance_repository, _processed, _outbox, clock = wiring
    ticket_id, ticket_cycle_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    workflow_instance_id = _seed_workflow(workflow_instance_repository, clock, ticket_id, ticket_cycle_id)

    applied = service.consume_cancelled(_cancelled_command(ticket_id, ticket_cycle_id))

    assert applied is True
    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.CANCELLED


def test_ticket_cancelled_cancels_every_active_workflow_type_for_that_ticket_cycle(wiring) -> None:
    """"At most one active instance per ticketId+ticketCycleId+workflowType" is a
    *per-type* invariant — more than one distinct workflow_type could in principle be
    active for the same ticket cycle, and a ticket-level cancel must reach all of them.
    """
    service, workflow_instance_repository, _processed, _outbox, clock = wiring
    ticket_id, ticket_cycle_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    first = _seed_workflow(workflow_instance_repository, clock, ticket_id, ticket_cycle_id, workflow_type=WorkflowType("TICKET_TRIAGE"))
    second = _seed_workflow(workflow_instance_repository, clock, ticket_id, ticket_cycle_id, workflow_type=WorkflowType("OTHER_TYPE"))

    service.consume_cancelled(_cancelled_command(ticket_id, ticket_cycle_id))

    assert workflow_instance_repository.find_by_id(first).state is WorkflowState.CANCELLED
    assert workflow_instance_repository.find_by_id(second).state is WorkflowState.CANCELLED


def test_ticket_cancelled_leaves_a_different_ticket_cycles_workflow_alone(wiring) -> None:
    service, workflow_instance_repository, _processed, _outbox, clock = wiring
    ticket_id = TicketId(uuid.uuid4())
    target_cycle, other_cycle = TicketCycleId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    target = _seed_workflow(workflow_instance_repository, clock, ticket_id, target_cycle)
    other = _seed_workflow(workflow_instance_repository, clock, ticket_id, other_cycle)

    service.consume_cancelled(_cancelled_command(ticket_id, target_cycle))

    assert workflow_instance_repository.find_by_id(target).state is WorkflowState.CANCELLED
    assert workflow_instance_repository.find_by_id(other).state is WorkflowState.RUNNING


def test_ticket_cancelled_with_no_active_workflow_is_a_harmless_no_op(wiring) -> None:
    service = wiring[0]

    applied = service.consume_cancelled(_cancelled_command(TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4())))

    assert applied is True  # newly processed, even though there was nothing to cancel


def test_ticket_cancelled_is_idempotent_under_the_same_event_id(wiring) -> None:
    service, _workflow_instance_repository, processed_event_repository, _outbox, _clock = wiring
    ticket_id, ticket_cycle_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    command = _cancelled_command(ticket_id, ticket_cycle_id)

    first = service.consume_cancelled(command)
    second = service.consume_cancelled(command)

    assert first is True
    assert second is False
    assert processed_event_repository.is_processed(command.event_id, CONSUMER_NAME) is True


def test_ticket_reopened_cancels_the_previous_cycles_active_workflow(wiring) -> None:
    service, workflow_instance_repository, _processed, _outbox, clock = wiring
    ticket_id = TicketId(uuid.uuid4())
    previous_cycle, new_cycle = TicketCycleId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    previous_workflow = _seed_workflow(workflow_instance_repository, clock, ticket_id, previous_cycle)

    applied = service.consume_reopened(_reopened_command(ticket_id, previous_cycle, new_cycle))

    assert applied is True
    assert workflow_instance_repository.find_by_id(previous_workflow).state is WorkflowState.CANCELLED


def test_ticket_reopened_does_not_touch_an_already_terminal_workflow(wiring) -> None:
    service, workflow_instance_repository, _processed, _outbox, clock = wiring
    ticket_id, previous_cycle = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    completed = _seed_workflow(workflow_instance_repository, clock, ticket_id, previous_cycle, state=WorkflowState.COMPLETED)

    service.consume_reopened(_reopened_command(ticket_id, previous_cycle))

    # Still COMPLETED, not CANCELLED — a workflow that already finished on its own before
    # the reopen arrived must not be retroactively marked cancelled.
    assert workflow_instance_repository.find_by_id(completed).state is WorkflowState.COMPLETED


def test_ticket_cancelled_and_ticket_reopened_do_not_share_a_dedup_record(wiring) -> None:
    """Both event types funnel through the same CONSUMER_NAME (one logical consumer),
    so a cancelled and a reopened event for the very same event_id — an unlikely but
    possible producer-side collision — must still be tracked as the same dedup entry,
    not two different ones a naive per-method key might create.
    """
    service, _workflow_instance_repository, processed_event_repository, _outbox, _clock = wiring
    ticket_id, ticket_cycle_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4())
    shared_event_id = "evt-shared-1"

    first = service.consume_cancelled(_cancelled_command(ticket_id, ticket_cycle_id, event_id=shared_event_id))
    second = service.consume_reopened(_reopened_command(ticket_id, ticket_cycle_id, event_id=shared_event_id))

    assert first is True
    assert second is False
    assert processed_event_repository.is_processed(shared_event_id, CONSUMER_NAME) is True
