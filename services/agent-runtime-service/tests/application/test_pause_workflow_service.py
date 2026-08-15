from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import PauseWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.pause_workflow import PauseWorkflowService
from agentruntime.domain.enums import CheckpointType, WorkflowState
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
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    service = PauseWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )

    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, workflow_instance_id, outbox_repository, checkpoint_repository


def test_pauses_a_running_workflow_and_increments_pause_generation(wiring) -> None:
    service, workflow_instance_id, outbox_repository, _checkpoint_repository = wiring

    view = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    assert view.state is WorkflowState.PAUSED
    assert view.workflow_version == 2
    assert view.pause_generation == 1
    assert len(outbox_repository.recorded()) == 1


def test_pausing_writes_a_pause_point_checkpoint(wiring) -> None:
    """SPEC-ARO-012 08-transaction-and-outbox §"Pause Transaction" step 6: "Write PAUSED
    checkpoint." — PAUSED is exactly the "recoverable waiting state" 02-business-invariants
    §"Checkpoint Invariants" requires one for.
    """
    service, workflow_instance_id, _outbox_repository, checkpoint_repository = wiring

    view = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    checkpoints = checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert [c.type for c in checkpoints] == [CheckpointType.PAUSE_POINT]
    assert checkpoints[0].workflow_version == view.workflow_version
    assert checkpoints[0].checksum
    assert checkpoints[0].cursor is None


def test_duplicate_pause_with_the_same_key_returns_the_cached_result_without_publishing_again(wiring) -> None:
    service, workflow_instance_id, outbox_repository, checkpoint_repository = wiring
    service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    second = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    assert second.state is WorkflowState.PAUSED
    assert second.workflow_version == 2
    assert len(outbox_repository.recorded()) == 1
    assert len(checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)) == 1


def test_pause_with_a_different_key_while_already_paused_returns_the_current_state_without_publishing_again(wiring) -> None:
    """09-concurrency-and-idempotency §"How Pause / Resume Is Idempotent": "If workflow is
    already PAUSED without the same idempotency key, return current paused state and do
    not publish another event." — unlike Resume, a *new* key against an already-paused
    workflow is not a conflict. The same leniency applies to the checkpoint: no new pause
    actually happened, so no second PAUSE_POINT checkpoint is written either.
    """
    service, workflow_instance_id, outbox_repository, checkpoint_repository = wiring
    first = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))

    second = service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-2")))

    assert second.state is WorkflowState.PAUSED
    assert second.workflow_version == first.workflow_version
    assert len(outbox_repository.recorded()) == 1
    assert len(checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)) == 1


def test_pausing_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.pause(PauseWorkflowCommand(WorkflowInstanceId.new_id(), IdempotencyKey("pause-x")))
