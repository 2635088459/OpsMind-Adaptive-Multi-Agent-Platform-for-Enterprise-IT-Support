from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.records import (
    AgentTaskRecord,
    ToolRequestRecord,
    WorkflowInstanceRecord,
)
from agentruntime.application.services.recover_stale_tool_waits import RecoverStaleToolWaitsService
from agentruntime.domain.enums import AgentTaskState, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    DefinitionVersion,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit

_TIMEOUT_SECONDS = 300.0


@pytest.fixture
def wiring():
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    clock = FakeClock()
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    service = RecoverStaleToolWaitsService(
        agent_task_repository, workflow_instance_repository, tool_request_repository,
        clock, telemetry, audit_recorder, _TIMEOUT_SECONDS,
    )
    return service, agent_task_repository, workflow_instance_repository, tool_request_repository, clock


def _seed(agent_repo, wf_repo, tr_repo, clock, *, wf_state, task_state, tool_status, task_age_seconds):
    now = clock.now()
    wf = WorkflowInstanceRecord(
        id=WorkflowInstanceId.new_id(), ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("conversational_intake"), definition_id=WorkflowDefinitionId("conv-v1"),
        definition_version=DefinitionVersion(1), state=wf_state, workflow_version=1, pause_generation=0,
        current_checkpoint_id=None, completed_at=None, created_at=now, updated_at=now,
    )
    wf_repo.save(wf)

    task_id = AgentTaskId.new_id()
    task_updated = now - timedelta(seconds=task_age_seconds)
    agent_repo.save(AgentTaskRecord(
        id=task_id, workflow_instance_id=wf.id, task_key="message-1", task_type="process_user_message",
        depends_on_task_keys=frozenset(), state=task_state, task_version=1, worker_id=None,
        lease_token=None, lease_expires_at=None, result_payload=None, failure_reason=None,
        pause_generation=0, created_at=task_updated, updated_at=task_updated,
    ))

    tr_id = ToolRequestId.new_id()
    tr_repo.save(ToolRequestRecord(
        id=tr_id, workflow_instance_id=wf.id, agent_task_id=task_id,
        preceding_checkpoint_id=CheckpointId(uuid.uuid4()), tool_name="send_password_reset", request_payload="{}",
        status=tool_status, created_at=task_updated, updated_at=task_updated,
    ))
    return wf.id, task_id, tr_id


def test_stale_tool_wait_fails_the_turn_and_wakes_the_workflow(wiring):
    service, agent_repo, wf_repo, tr_repo, clock = wiring
    wf_id, task_id, tr_id = _seed(
        agent_repo, wf_repo, tr_repo, clock,
        wf_state=WorkflowState.WAITING_FOR_TOOL, task_state=AgentTaskState.WAITING_TOOL,
        tool_status=ToolRequestStatus.DISPATCHED, task_age_seconds=_TIMEOUT_SECONDS + 60,
    )

    report = service.scan_and_recover()

    assert report.scanned == 1 and report.timed_out == 1
    assert agent_repo.find_by_id(task_id).state is AgentTaskState.FAILED_FINAL
    assert wf_repo.find_by_id(wf_id).state is WorkflowState.RUNNING
    assert tr_repo.find_by_id(tr_id).status is ToolRequestStatus.FAILED


def test_a_recent_tool_wait_is_left_alone(wiring):
    service, agent_repo, wf_repo, tr_repo, clock = wiring
    wf_id, task_id, _ = _seed(
        agent_repo, wf_repo, tr_repo, clock,
        wf_state=WorkflowState.WAITING_FOR_TOOL, task_state=AgentTaskState.WAITING_TOOL,
        tool_status=ToolRequestStatus.DISPATCHED, task_age_seconds=_TIMEOUT_SECONDS - 60,
    )

    report = service.scan_and_recover()

    assert report.scanned == 0 and report.timed_out == 0
    assert agent_repo.find_by_id(task_id).state is AgentTaskState.WAITING_TOOL
    assert wf_repo.find_by_id(wf_id).state is WorkflowState.WAITING_FOR_TOOL


def test_workflow_that_already_moved_on_is_not_forced_back_to_running(wiring):
    service, agent_repo, wf_repo, tr_repo, clock = wiring
    # task is stale, but the workflow was cancelled in the meantime
    wf_id, task_id, _ = _seed(
        agent_repo, wf_repo, tr_repo, clock,
        wf_state=WorkflowState.CANCELLED, task_state=AgentTaskState.WAITING_TOOL,
        tool_status=ToolRequestStatus.DISPATCHED, task_age_seconds=_TIMEOUT_SECONDS + 60,
    )

    report = service.scan_and_recover()

    # the abandoned turn is still failed, but the terminal workflow is left as-is
    assert report.timed_out == 1
    assert agent_repo.find_by_id(task_id).state is AgentTaskState.FAILED_FINAL
    assert wf_repo.find_by_id(wf_id).state is WorkflowState.CANCELLED


def test_a_concurrent_scan_losing_the_save_race_is_tolerated(wiring):
    from agentruntime.application.exceptions import AgentTaskVersionConflictException

    service, agent_repo, wf_repo, tr_repo, clock = wiring
    _seed(
        agent_repo, wf_repo, tr_repo, clock,
        wf_state=WorkflowState.WAITING_FOR_TOOL, task_state=AgentTaskState.WAITING_TOOL,
        tool_status=ToolRequestStatus.DISPATCHED, task_age_seconds=_TIMEOUT_SECONDS + 60,
    )
    original_save = agent_repo.save

    def conflicting_save(record):
        raise AgentTaskVersionConflictException()

    agent_repo.save = conflicting_save  # another scan moved the task on first
    try:
        report = service.scan_and_recover()
    finally:
        agent_repo.save = original_save

    assert report.scanned == 1 and report.timed_out == 0


def test_an_already_completed_tool_request_is_not_re_failed(wiring):
    service, agent_repo, wf_repo, tr_repo, clock = wiring
    wf_id, task_id, tr_id = _seed(
        agent_repo, wf_repo, tr_repo, clock,
        wf_state=WorkflowState.WAITING_FOR_TOOL, task_state=AgentTaskState.WAITING_TOOL,
        tool_status=ToolRequestStatus.COMPLETED, task_age_seconds=_TIMEOUT_SECONDS + 60,
    )

    service.scan_and_recover()

    assert tr_repo.find_by_id(tr_id).status is ToolRequestStatus.COMPLETED
