from __future__ import annotations

import uuid

import pytest

from agentruntime.application.commands import PauseWorkflowCommand, ResumeWorkflowCommand
from agentruntime.application.exceptions import PauseCheckpointNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.pause_workflow import PauseWorkflowService
from agentruntime.application.services.resume_workflow import ResumeWorkflowService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowTransitionException
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
    pause_service = PauseWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    service = ResumeWorkflowService(
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
    # A real pause, not a hand-crafted PAUSED record — this is what wires up the
    # PAUSE_POINT checkpoint SPEC-ARO-014/015's own resume-time check depends on.
    pause_service.pause(PauseWorkflowCommand(workflow_instance_id, IdempotencyKey("pause-1")))
    return service, workflow_instance_id, workflow_instance_repository, checkpoint_repository


def test_resumes_a_paused_workflow_without_changing_pause_generation(wiring) -> None:
    service, workflow_instance_id, *_rest = wiring

    view = service.resume(ResumeWorkflowCommand(workflow_instance_id, IdempotencyKey("resume-1")))

    assert view.state is WorkflowState.RUNNING
    assert view.workflow_version == 3
    assert view.pause_generation == 1


def test_duplicate_resume_with_the_same_key_returns_the_cached_result(wiring) -> None:
    service, workflow_instance_id, *_rest = wiring
    service.resume(ResumeWorkflowCommand(workflow_instance_id, IdempotencyKey("resume-1")))

    second = service.resume(ResumeWorkflowCommand(workflow_instance_id, IdempotencyKey("resume-1")))

    assert second.workflow_version == 3


def test_resume_with_a_different_key_while_already_running_is_rejected(wiring) -> None:
    """09-concurrency-and-idempotency §"How Pause / Resume Is Idempotent": "If workflow
    already resumed, use idempotency key to decide saved response or conflict" — unlike
    Pause, a genuinely new key reaches the domain guard, which rejects resuming a
    non-PAUSED workflow.
    """
    service, workflow_instance_id, *_rest = wiring
    service.resume(ResumeWorkflowCommand(workflow_instance_id, IdempotencyKey("resume-1")))

    with pytest.raises(InvalidWorkflowTransitionException):
        service.resume(ResumeWorkflowCommand(workflow_instance_id, IdempotencyKey("resume-2")))


def test_resuming_a_paused_workflow_with_no_pause_checkpoint_is_rejected(wiring) -> None:
    """SPEC-ARO-014/015 04-use-cases UC-07 Resume step 4: "Read the PAUSED checkpoint."
    Simulates the invariant-violation scenario directly (a PAUSED row with no PAUSE_POINT
    checkpoint behind it) rather than trying to reach it through the real PauseWorkflowService,
    which always writes one — this can only happen if that invariant was broken some other
    way (data loss, or a future bypass of PauseWorkflowService).
    """
    service, _workflow_instance_id, workflow_instance_repository, checkpoint_repository = wiring
    orphaned_id = WorkflowInstanceId.new_id()
    now = workflow_instance_repository.find_by_id(_workflow_instance_id).updated_at
    workflow_instance_repository.save(WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=orphaned_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.PAUSED, workflow_version=1, pause_generation=1,
        created_at=now, updated_at=now,
    ))

    with pytest.raises(PauseCheckpointNotFoundException):
        service.resume(ResumeWorkflowCommand(orphaned_id, IdempotencyKey("resume-orphan-1")))

    assert checkpoint_repository.find_by_workflow_instance_id(orphaned_id) == []
