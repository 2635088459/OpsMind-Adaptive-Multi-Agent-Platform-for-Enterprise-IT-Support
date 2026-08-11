from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.commands import RecoveryCommand
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
from agentruntime.infrastructure.persistence.in_memory import InMemoryAgentTaskRepository, InMemoryCheckpointRepository, InMemoryWorkflowInstanceRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    clock = FakeClock()
    service = RecoverWorkflowService(workflow_instance_repository, checkpoint_repository, agent_task_repository, clock)

    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))

    checkpoint_repository.save(CheckpointRecord(CheckpointId.new_id(), workflow_instance_id, CheckpointType.PRE_TOOL_CALL, 1, "{}", now))
    checkpoint_repository.save(CheckpointRecord(CheckpointId.new_id(), workflow_instance_id, CheckpointType.WAIT_STATE, 1, "{}", now))

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
    return service, workflow_instance_id


def test_reports_checkpoints_and_open_leases_from_persisted_state(wiring) -> None:
    service, workflow_instance_id = wiring

    report = service.recover(RecoveryCommand(workflow_instance_id))

    assert report.recoverable_checkpoint_count == 2
    assert report.open_lease_count == 1
    assert report.state is WorkflowState.RUNNING


def test_rejects_recovery_when_the_expected_definition_version_does_not_match(wiring) -> None:
    service, workflow_instance_id = wiring

    with pytest.raises(DefinitionVersionMismatchException):
        service.recover(RecoveryCommand(workflow_instance_id, DefinitionVersion(2)))


def test_recovering_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.recover(RecoveryCommand(WorkflowInstanceId.new_id()))
