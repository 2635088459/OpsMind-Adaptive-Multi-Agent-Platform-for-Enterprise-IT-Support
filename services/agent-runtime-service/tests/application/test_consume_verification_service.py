from __future__ import annotations

import dataclasses
import json
import uuid

import pytest

from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.records import AgentTaskRecord, CheckpointRecord, WorkflowInstanceRecord
from agentruntime.application.services import task_graph_codec
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.consume_verification import ConsumeVerificationService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain.enums import AgentTaskState, CheckpointType, JoinPolicy, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    DefinitionVersion,
    TicketCycleId,
    TicketId,
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
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
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
    service = ConsumeVerificationService(
        workflow_instance_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )

    now = clock.now()
    workflow_instance_id = WorkflowInstanceId.new_id()
    running = WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(running)
    # No spec has built an entry path into WAITING_FOR_VERIFICATION either — seeded
    # directly, one version at a time per the repository's own CAS.
    workflow_instance_repository.save(dataclasses.replace(running, state=WorkflowState.WAITING_FOR_VERIFICATION, workflow_version=2))

    return service, workflow_instance_id, workflow_instance_repository, checkpoint_repository, outbox_repository, agent_task_repository


def _seed_a_fully_completed_task_graph(agent_task_repository, checkpoint_repository, workflow_instance_id: WorkflowInstanceId, now) -> None:
    """determine_settlement() re-derives the task graph from the STARTED checkpoint and
    checks every materialized node reached a terminal state
    (coordinator.is_workflow_settled()) — a single "collect" node, already COMPLETED, is
    the minimal graph a passing verification can settle straight to COMPLETED.
    """
    graph = TaskGraph((TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)
    checkpoint_repository.save(CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload=task_graph_codec.encode(definition), recorded_at=now,
        workflow_version=1, checksum="test-checksum",
    ))
    agent_task_repository.save(AgentTaskRecord(
        id=AgentTaskId.new_id(), workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.COMPLETED, task_version=1, worker_id="worker-1",
        lease_token=None, lease_expires_at=None, result_payload="diagnostics collected", failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now,
    ))


def _payload(passed: bool, verification_request_id: str | None = None, evidence: str | None = "looks good") -> str:
    body: dict[str, object] = {"verificationRequestId": verification_request_id or str(uuid.uuid4()), "passed": passed}
    if evidence is not None:
        body["evidence"] = evidence
    return json.dumps(body)


def test_a_passing_verification_wakes_the_workflow_to_running(wiring) -> None:
    service, workflow_instance_id, workflow_instance_repository, _checkpoint_repository, _outbox_repository, _agent_task_repository = wiring

    service.apply(workflow_instance_id, _payload(True))

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.RUNNING
    assert workflow.workflow_version == 3


def test_a_passing_verification_settles_a_fully_done_task_graph_to_completed(wiring) -> None:
    """UC-05 step 3's own "Runtime 可进入完成路径": when the task graph the STARTED
    checkpoint describes has nothing left outstanding, waking from the verification wait
    immediately auto-completes — mirroring ConsumeToolResultService's own settlement
    reuse.
    """
    service, workflow_instance_id, workflow_instance_repository, checkpoint_repository, outbox_repository, agent_task_repository = wiring
    now = workflow_instance_repository.find_by_id(workflow_instance_id).created_at
    _seed_a_fully_completed_task_graph(agent_task_repository, checkpoint_repository, workflow_instance_id, now)

    service.apply(workflow_instance_id, _payload(True))

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.COMPLETED
    assert any(record.event_type == "workflow.completed.v1" for record in outbox_repository.recorded())


def test_a_failing_verification_fails_the_workflow_with_an_auditable_reason(wiring) -> None:
    service, workflow_instance_id, workflow_instance_repository, _checkpoint_repository, outbox_repository, _agent_task_repository = wiring

    service.apply(workflow_instance_id, _payload(False, verification_request_id="req-1", evidence="checks failed: disk still full"))

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.FAILED
    assert any(record.event_type == "workflow.failed.v1" for record in outbox_repository.recorded())


def test_a_delivery_for_a_workflow_no_longer_waiting_for_verification_is_a_no_op(wiring) -> None:
    service, workflow_instance_id, workflow_instance_repository, _checkpoint_repository, _outbox_repository, _agent_task_repository = wiring
    service.apply(workflow_instance_id, _payload(True))
    woken = workflow_instance_repository.find_by_id(workflow_instance_id)

    service.apply(workflow_instance_id, _payload(True))

    unchanged = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert unchanged.workflow_version == woken.workflow_version


def test_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.apply(WorkflowInstanceId.new_id(), _payload(True))
