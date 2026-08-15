from __future__ import annotations

import dataclasses
import json
import uuid

import pytest

from agentruntime.application.exceptions import AgentTaskNotFoundException, ToolRequestNotFoundException
from agentruntime.application.records import AgentTaskRecord, CheckpointRecord, ToolRequestRecord, WorkflowInstanceRecord
from agentruntime.application.services import task_graph_codec
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.consume_tool_result import ConsumeToolResultService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain.enums import AgentTaskState, CheckpointType, JoinPolicy, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    DefinitionVersion,
    LeaseToken,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.domain.task_graph import TaskGraph, TaskNode, WorkflowDefinition
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    tool_request_repository = InMemoryToolRequestRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    service = ConsumeToolResultService(
        tool_request_repository, agent_task_repository, workflow_instance_repository, checkpoint_repository,
        outbox_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )

    # The repositories' own optimistic-version CAS only accepts an insert at version 1
    # (see tests/application/test_request_tool_service.py's `_start_workflow_instance`), so
    # reaching WAITING_FOR_TOOL/WAITING_TOOL at version 2 means legitimately re-saving one
    # version at a time, not constructing a record at that version directly.
    now = clock.now()
    workflow_instance_id = WorkflowInstanceId.new_id()
    running_workflow = WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(running_workflow)
    workflow_instance_repository.save(dataclasses.replace(running_workflow, state=WorkflowState.WAITING_FOR_TOOL, workflow_version=2))

    agent_task_id = AgentTaskId.new_id()
    claimed_task = AgentTaskRecord(
        id=agent_task_id, workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.CLAIMED, task_version=1, worker_id="worker-1",
        lease_token=LeaseToken.new_token(), lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now,
    )
    agent_task_repository.save(claimed_task)
    agent_task_repository.save(dataclasses.replace(claimed_task, state=AgentTaskState.WAITING_TOOL, task_version=2))
    tool_request_id = ToolRequestId.new_id()
    tool_request_repository.save(ToolRequestRecord(
        id=tool_request_id, workflow_instance_id=workflow_instance_id, agent_task_id=agent_task_id,
        preceding_checkpoint_id=CheckpointId.new_id(), tool_name="restart_service", request_payload="{}",
        status=ToolRequestStatus.DISPATCHED, created_at=now, updated_at=now,
    ))
    # CoordinateAgentTasksService.determine_settlement() re-derives the task graph from the
    # STARTED checkpoint (there is no Workflow Definition catalog table) — a single-node
    # graph here means completing "collect" settles the whole workflow, exercising the
    # same auto-complete path CompleteAgentTaskService's own settlement tests rely on.
    graph = TaskGraph((TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)
    checkpoint_repository.save(CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload=task_graph_codec.encode(definition), recorded_at=now,
        workflow_version=1, checksum="test-checksum",
    ))
    return (
        service, tool_request_id, agent_task_id, workflow_instance_id, tool_request_repository, agent_task_repository,
        workflow_instance_repository, checkpoint_repository, outbox_repository,
    )


def _payload(tool_request_id: ToolRequestId, status: str = "COMPLETED", result_payload: str | None = "diagnostics ok") -> str:
    body = {"toolRequestId": str(tool_request_id), "status": status}
    if result_payload is not None:
        body["resultPayload"] = result_payload
    return json.dumps(body)


def test_a_completed_tool_result_completes_the_agent_task_and_wakes_the_workflow(wiring) -> None:
    (
        service, tool_request_id, agent_task_id, workflow_instance_id, tool_request_repository, agent_task_repository,
        workflow_instance_repository, _checkpoint_repository, outbox_repository,
    ) = wiring

    service.apply(_payload(tool_request_id))

    task = agent_task_repository.find_by_id(agent_task_id)
    assert task.state is AgentTaskState.COMPLETED
    assert task.result_payload == "diagnostics ok"

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.COMPLETED  # single-task graph auto-settles once the task completes

    tool_request = tool_request_repository.find_by_id(tool_request_id)
    assert tool_request.status is ToolRequestStatus.COMPLETED
    assert tool_request.result_payload == "diagnostics ok"

    assert any(record.event_type == "agent.task.completed.v1" for record in outbox_repository.recorded())


def test_a_completed_tool_result_logs_the_published_event_with_the_required_observability_fields(
    wiring, caplog: pytest.LogCaptureFixture
) -> None:
    (
        service, tool_request_id, agent_task_id, workflow_instance_id, _tool_request_repository, _agent_task_repository,
        _workflow_instance_repository, _checkpoint_repository, outbox_repository,
    ) = wiring

    with caplog.at_level("INFO", logger="agentruntime.application.services.consume_tool_result"):
        service.apply(_payload(tool_request_id))

    outbox_record = next(r for r in outbox_repository.recorded() if r.event_type == "agent.task.completed.v1")
    [record] = [r for r in caplog.records if "agent task event published" in r.message]
    assert "event_type=agent.task.completed.v1" in record.message
    assert f"workflow_instance_id={workflow_instance_id}" in record.message
    assert f"agent_task_id={agent_task_id}" in record.message
    assert "worker_id=worker-1" in record.message
    assert f"correlation_id={outbox_record.correlation_id}" in record.message


def test_a_failed_tool_result_fails_the_agent_task(wiring) -> None:
    (
        service, tool_request_id, agent_task_id, workflow_instance_id, tool_request_repository, agent_task_repository,
        _workflow_instance_repository, _checkpoint_repository, outbox_repository,
    ) = wiring

    service.apply(_payload(tool_request_id, status="FAILED", result_payload="tool exhausted retries"))

    task = agent_task_repository.find_by_id(agent_task_id)
    assert task.state is AgentTaskState.FAILED_FINAL
    assert task.failure_reason == "tool exhausted retries"

    assert any(record.event_type == "agent.task.failed.v1" for record in outbox_repository.recorded())


def test_a_tool_result_wakes_the_workflow_back_to_running(wiring) -> None:
    """SPEC-ARO-019's wait_for_tool()'s own counterpart — proved independently of
    settlement by checking the workflow_version bump, not just the terminal state
    settlement produces.
    """
    service, tool_request_id, _agent_task_id, workflow_instance_id, *_rest, workflow_instance_repository, _checkpoint_repository, _outbox = wiring

    service.apply(_payload(tool_request_id))

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    # RUNNING (wake) -> COMPLETED (settlement): two version bumps from the WAITING_FOR_TOOL
    # baseline of 2.
    assert workflow.workflow_version == 4


def test_a_tool_result_writes_an_after_task_checkpoint(wiring) -> None:
    service, tool_request_id, _agent_task_id, workflow_instance_id, *_rest, checkpoint_repository, _outbox = wiring

    service.apply(_payload(tool_request_id))

    checkpoints = checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert any(c.type is CheckpointType.AFTER_TASK for c in checkpoints)


def test_a_tool_result_for_an_already_resolved_tool_request_is_a_no_op(wiring) -> None:
    """UC-04 step 3: "校验 Tool Request 状态仍等待结果" — a duplicate delivery (e.g. under a
    different eventId than the first one, past the processed_events dedup) must not
    re-apply an outcome that already landed.
    """
    (
        service, tool_request_id, agent_task_id, _workflow_instance_id, _tool_request_repository, agent_task_repository,
        *_rest,
    ) = wiring
    service.apply(_payload(tool_request_id))
    completed_task = agent_task_repository.find_by_id(agent_task_id)

    service.apply(_payload(tool_request_id, result_payload="a second, different result"))

    unchanged_task = agent_task_repository.find_by_id(agent_task_id)
    assert unchanged_task.task_version == completed_task.task_version
    assert unchanged_task.result_payload == completed_task.result_payload


def test_an_unknown_tool_request_id_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(ToolRequestNotFoundException):
        service.apply(_payload(ToolRequestId.new_id()))


def test_an_orphaned_tool_request_with_no_agent_task_is_rejected(wiring) -> None:
    """Defensive: should never happen in practice (Tool Requests are always created
    against a real Agent Task), but this service must not silently proceed if it does.
    """
    (
        service, _tool_request_id, agent_task_id, workflow_instance_id, tool_request_repository, agent_task_repository,
        *_rest,
    ) = wiring
    orphan_tool_request_id = ToolRequestId.new_id()
    tool_request_repository.save(ToolRequestRecord(
        id=orphan_tool_request_id, workflow_instance_id=workflow_instance_id, agent_task_id=AgentTaskId.new_id(),
        preceding_checkpoint_id=CheckpointId.new_id(), tool_name="restart_service", request_payload="{}",
        status=ToolRequestStatus.DISPATCHED, created_at=agent_task_repository.find_by_id(agent_task_id).created_at,
        updated_at=agent_task_repository.find_by_id(agent_task_id).created_at,
    ))

    with pytest.raises(AgentTaskNotFoundException):
        service.apply(_payload(orphan_tool_request_id))
