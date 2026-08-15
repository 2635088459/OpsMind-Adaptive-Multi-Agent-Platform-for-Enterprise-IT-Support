from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import CancelWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.cancel_workflow import CancelWorkflowService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException
from agentruntime.domain.ids import DefinitionVersion, IdempotencyKey, TicketCycleId, TicketId, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    clock = FakeClock()
    _telemetry, audit_recorder = build_telemetry_collaborators(clock)
    service = CancelWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        audit_recorder,
    )

    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, workflow_instance_id, outbox_repository


def test_cancels_a_running_workflow_and_publishes_the_cancelled_event_with_reason(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring

    view = service.cancel(CancelWorkflowCommand(workflow_instance_id, IdempotencyKey("cancel-1"), "ticket cancelled upstream"))

    assert view.state is WorkflowState.CANCELLED
    assert view.workflow_version == 2
    [record] = outbox_repository.recorded()
    assert record.event_type == "workflow.cancelled.v1"
    assert "ticket cancelled upstream" in record.payload


def test_cancel_command_rejects_a_blank_reason() -> None:
    with pytest.raises(ValueError):
        CancelWorkflowCommand(WorkflowInstanceId.new_id(), IdempotencyKey("cancel-x"), "   ")


def test_duplicate_cancel_with_the_same_key_returns_the_cached_result_without_publishing_again(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring
    service.cancel(CancelWorkflowCommand(workflow_instance_id, IdempotencyKey("cancel-1"), "ticket cancelled upstream"))

    second = service.cancel(CancelWorkflowCommand(workflow_instance_id, IdempotencyKey("cancel-1"), "ticket cancelled upstream"))

    assert second.state is WorkflowState.CANCELLED
    assert len(outbox_repository.recorded()) == 1


def test_cancel_with_a_different_key_while_already_cancelled_is_rejected(wiring) -> None:
    service, workflow_instance_id = wiring[0], wiring[1]
    service.cancel(CancelWorkflowCommand(workflow_instance_id, IdempotencyKey("cancel-1"), "ticket cancelled upstream"))

    with pytest.raises(InvalidWorkflowStateException):
        service.cancel(CancelWorkflowCommand(workflow_instance_id, IdempotencyKey("cancel-2"), "a second reason"))


def test_cancelling_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.cancel(CancelWorkflowCommand(WorkflowInstanceId.new_id(), IdempotencyKey("cancel-x"), "unreachable"))
