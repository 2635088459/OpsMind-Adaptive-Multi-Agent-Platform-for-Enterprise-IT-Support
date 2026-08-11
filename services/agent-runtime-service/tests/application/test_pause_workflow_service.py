from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import PauseWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.pause_workflow import PauseWorkflowService
from agentruntime.domain.enums import WorkflowState
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
    service = PauseWorkflowService(workflow_instance_repository, outbox_repository, command_idempotency_repository, clock)

    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, workflow_instance_id, outbox_repository


def test_pauses_a_running_workflow_and_increments_pause_generation(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring

    view = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    assert view.state is WorkflowState.PAUSED
    assert view.workflow_version == 2
    assert view.pause_generation == 1
    assert len(outbox_repository.recorded()) == 1


def test_duplicate_pause_with_the_same_key_returns_the_cached_result_without_publishing_again(wiring) -> None:
    service, workflow_instance_id, outbox_repository = wiring
    service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    second = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    assert second.state is WorkflowState.PAUSED
    assert second.workflow_version == 2
    assert len(outbox_repository.recorded()) == 1


def test_pause_with_a_different_key_while_already_paused_returns_the_current_state_without_publishing_again(wiring) -> None:
    """09-concurrency-and-idempotency §"How Pause / Resume Is Idempotent": "If workflow is
    already PAUSED without the same idempotency key, return current paused state and do
    not publish another event." — unlike Resume, a *new* key against an already-paused
    workflow is not a conflict.
    """
    service, workflow_instance_id, outbox_repository = wiring
    first = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    second = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-2")))

    assert second.state is WorkflowState.PAUSED
    assert second.workflow_version == first.workflow_version
    assert len(outbox_repository.recorded()) == 1


def test_pausing_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.pause(PauseWorkflowCommand(WorkflowInstanceId.new_id(), IdempotencyKey("pause-x")))
