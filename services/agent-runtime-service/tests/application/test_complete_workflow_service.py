from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import CompleteWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException
from agentruntime.domain.ids import DefinitionVersion, IdempotencyKey, TicketCycleId, TicketId, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.infrastructure.persistence.in_memory import InMemoryCommandIdempotencyRepository, InMemoryOutboxRepository, InMemoryWorkflowInstanceRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    service = CompleteWorkflowService(workflow_instance_repository, outbox_repository, command_idempotency_repository, clock)

    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, workflow_instance_id, outbox_repository


def test_completes_a_running_workflow_and_publishes_the_completed_event(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring

    view = service.complete(CompleteWorkflowCommand(workflow_instance_id, IdempotencyKey("complete-1")))

    assert view.state is WorkflowState.COMPLETED
    assert view.workflow_version == 2
    [record] = outbox_repository.recorded()
    assert record.event_type == "agent_runtime.workflow.completed"


def test_duplicate_complete_with_the_same_key_returns_the_cached_result_without_publishing_again(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring
    service.complete(CompleteWorkflowCommand(workflow_instance_id, IdempotencyKey("complete-1")))

    second = service.complete(CompleteWorkflowCommand(workflow_instance_id, IdempotencyKey("complete-1")))

    assert second.state is WorkflowState.COMPLETED
    assert second.workflow_version == 2
    assert len(outbox_repository.recorded()) == 1


def test_complete_with_a_different_key_while_already_completed_is_rejected(wiring) -> None:
    """Unlike Pause, completing an already-terminal workflow under a genuinely new key is a
    real error, not a repeatable no-op — the domain guard rejects a terminal current_state.
    """
    service, workflow_instance_id = wiring[0], wiring[1]
    service.complete(CompleteWorkflowCommand(workflow_instance_id, IdempotencyKey("complete-1")))

    with pytest.raises(InvalidWorkflowStateException):
        service.complete(CompleteWorkflowCommand(workflow_instance_id, IdempotencyKey("complete-2")))


def test_completing_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.complete(CompleteWorkflowCommand(WorkflowInstanceId.new_id(), IdempotencyKey("complete-x")))
