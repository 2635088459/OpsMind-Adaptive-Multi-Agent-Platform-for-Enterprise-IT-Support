from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.commands import ForceRecoverWorkflowCommand, RecoveryCommand
from agentruntime.application.exceptions import DefinitionVersionMismatchException, WorkflowInstanceNotFoundException
from agentruntime.application.records import AgentTaskRecord, CheckpointRecord, WorkflowInstanceRecord
from agentruntime.application.services.recover_workflow import RecoverWorkflowService
from agentruntime.domain.enums import AgentTaskState, CheckpointType, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    DefinitionVersion,
    LeaseToken,
    TicketCycleId,
    TicketId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
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
    checkpoint_repository = InMemoryCheckpointRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    service = RecoverWorkflowService(
        workflow_instance_repository, checkpoint_repository, agent_task_repository, clock, fail_workflow_service, telemetry,
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

    checkpoint_repository.save(CheckpointRecord(
        CheckpointId.new_id(), workflow_instance_id, CheckpointType.PRE_TOOL_CALL, 1, "{}", now, workflow_version=1, checksum="c1"
    ))
    checkpoint_repository.save(CheckpointRecord(
        CheckpointId.new_id(), workflow_instance_id, CheckpointType.WAIT_STATE, 1, "{}", now, workflow_version=1, checksum="c2"
    ))

    agent_task_repository.save(AgentTaskRecord(
        id=AgentTaskId.new_id(), workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.CLAIMED, task_version=1, worker_id="worker-1",
        lease_token=LeaseToken.new_token(), lease_expires_at=now + timedelta(minutes=5), result_payload=None,
        failure_reason=None, pause_generation=0, created_at=now, updated_at=now,
    ))
    agent_task_repository.save(AgentTaskRecord(
        id=AgentTaskId.new_id(), workflow_instance_id=workflow_instance_id, task_key="remediate", task_type="apply_fix",
        depends_on_task_keys=frozenset({"collect"}), state=AgentTaskState.PENDING, task_version=1, worker_id=None,
        lease_token=None, lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, workflow_instance_id, workflow_instance_repository, checkpoint_repository, outbox_repository, clock


def test_reports_checkpoints_and_open_leases_from_persisted_state(wiring) -> None:
    service, workflow_instance_id = wiring[0], wiring[1]

    report = service.recover(RecoveryCommand(workflow_instance_id))

    assert report.recoverable_checkpoint_count == 2
    assert report.open_lease_count == 1
    assert report.state is WorkflowState.RUNNING


def test_rejects_recovery_when_the_expected_definition_version_does_not_match(wiring) -> None:
    service, workflow_instance_id = wiring[0], wiring[1]

    with pytest.raises(DefinitionVersionMismatchException):
        service.recover(RecoveryCommand(workflow_instance_id, DefinitionVersion(2)))


def test_recovering_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.recover(RecoveryCommand(WorkflowInstanceId.new_id()))


def test_scan_and_recover_counts_every_non_terminal_instance(wiring) -> None:
    """SPEC-ARO-028 10-failure-handling §"Runtime 崩溃后怎么恢复": "Recovery worker 周期性
    扫描非终态 Workflow Instance" — the RUNNING instance the fixture already seeded is
    picked up by the scan, checkpoint-consistent (not PAUSED, so the check does not apply).
    """
    service, _workflow_instance_id, _workflow_instance_repository, _checkpoint_repository, _outbox_repository, _clock = wiring

    report = service.scan_and_recover(batch_size=50)

    assert report.scanned == 1
    assert report.checkpoint_inconsistent == 0


def test_scan_and_recover_flags_a_paused_workflow_whose_latest_checkpoint_is_not_pause_point(wiring) -> None:
    service, _workflow_instance_id, workflow_instance_repository, checkpoint_repository, outbox_repository, clock = wiring
    now = clock.now()
    paused_id = WorkflowInstanceId.new_id()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        current_checkpoint_id=None, completed_at=None,
        id=paused_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.PAUSED, workflow_version=1, pause_generation=1,
        created_at=now, updated_at=now,
    ))
    # No PAUSE_POINT checkpoint at all for this instance — an inconsistency a crash
    # between the state transition and the checkpoint write would produce.
    checkpoint_repository.save(CheckpointRecord(
        CheckpointId.new_id(), paused_id, CheckpointType.STARTED, 1, "{}", now, workflow_version=1, checksum="c1"
    ))

    report = service.scan_and_recover(batch_size=50)

    assert report.checkpoint_inconsistent == 1
    flagged = workflow_instance_repository.find_by_id(paused_id)
    assert flagged.state is WorkflowState.FAILED
    assert any(record.event_type == "workflow.failed.v1" for record in outbox_repository.recorded())


def test_scan_and_recover_leaves_a_correctly_paused_workflow_alone(wiring) -> None:
    service, _workflow_instance_id, workflow_instance_repository, checkpoint_repository, _outbox_repository, clock = wiring
    now = clock.now()
    paused_id = WorkflowInstanceId.new_id()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        current_checkpoint_id=None, completed_at=None,
        id=paused_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.PAUSED, workflow_version=1, pause_generation=1,
        created_at=now, updated_at=now,
    ))
    checkpoint_repository.save(CheckpointRecord(
        CheckpointId.new_id(), paused_id, CheckpointType.PAUSE_POINT, 1, "{}", now, workflow_version=1, checksum="c1"
    ))

    report = service.scan_and_recover(batch_size=50)

    assert report.checkpoint_inconsistent == 0
    assert workflow_instance_repository.find_by_id(paused_id).state is WorkflowState.PAUSED


def test_force_recover_flags_a_named_paused_instance_with_no_pause_point_checkpoint(wiring) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow" — the
    admin-triggered, single-instance counterpart to scan_and_recover().
    """
    service, _workflow_instance_id, workflow_instance_repository, checkpoint_repository, outbox_repository, clock = wiring
    now = clock.now()
    paused_id = WorkflowInstanceId.new_id()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        current_checkpoint_id=None, completed_at=None,
        id=paused_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.PAUSED, workflow_version=1, pause_generation=1,
        created_at=now, updated_at=now,
    ))
    checkpoint_repository.save(CheckpointRecord(
        CheckpointId.new_id(), paused_id, CheckpointType.STARTED, 1, "{}", now, workflow_version=1, checksum="c1"
    ))

    report = service.force_recover(ForceRecoverWorkflowCommand(paused_id))

    assert report.scanned == 1
    assert report.checkpoint_inconsistent == 1
    assert workflow_instance_repository.find_by_id(paused_id).state is WorkflowState.FAILED
    assert any(record.event_type == "workflow.failed.v1" for record in outbox_repository.recorded())


def test_force_recover_leaves_a_correctly_paused_instance_alone(wiring) -> None:
    service, _workflow_instance_id, workflow_instance_repository, checkpoint_repository, _outbox_repository, clock = wiring
    now = clock.now()
    paused_id = WorkflowInstanceId.new_id()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        current_checkpoint_id=None, completed_at=None,
        id=paused_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.PAUSED, workflow_version=1, pause_generation=1,
        created_at=now, updated_at=now,
    ))
    checkpoint_repository.save(CheckpointRecord(
        CheckpointId.new_id(), paused_id, CheckpointType.PAUSE_POINT, 1, "{}", now, workflow_version=1, checksum="c1"
    ))

    report = service.force_recover(ForceRecoverWorkflowCommand(paused_id))

    assert report.checkpoint_inconsistent == 0
    assert workflow_instance_repository.find_by_id(paused_id).state is WorkflowState.PAUSED


def test_force_recover_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.force_recover(ForceRecoverWorkflowCommand(WorkflowInstanceId.new_id()))
