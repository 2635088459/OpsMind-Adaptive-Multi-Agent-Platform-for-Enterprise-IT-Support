from __future__ import annotations

import dataclasses
import uuid

import pytest

from agentruntime.application.commands import CompleteAgentTaskCommand
from agentruntime.application.exceptions import (
    AgentTaskNotFoundException,
    ClaimTokenMismatchException,
    StalePauseGenerationException,
    StaleWorkflowVersionException,
    WorkflowInstanceVersionConflictException,
)
from agentruntime.application.records import AgentTaskRecord, CheckpointRecord, WorkflowInstanceRecord
from agentruntime.application.services import task_graph_codec
from agentruntime.application.services.complete_agent_task import CompleteAgentTaskService
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain.enums import AgentTaskState, CheckpointType, JoinPolicy, WorkflowState
from agentruntime.domain.exceptions import InvalidAgentTaskTransitionException
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    DefinitionVersion,
    IdempotencyKey,
    LeaseToken,
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

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    complete_workflow_service = CompleteWorkflowService(workflow_instance_repository, outbox_repository, command_idempotency_repository, clock)
    fail_workflow_service = FailWorkflowService(workflow_instance_repository, outbox_repository, command_idempotency_repository, clock)
    service = CompleteAgentTaskService(
        agent_task_repository, workflow_instance_repository, checkpoint_repository, outbox_repository,
        command_idempotency_repository, clock, coordinate_agent_tasks_service,
        complete_workflow_service, fail_workflow_service,
    )

    now = clock.now()
    workflow_instance_id = WorkflowInstanceId.new_id()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))

    claim_token = LeaseToken.new_token()
    agent_task_id = AgentTaskId.new_id()
    agent_task_repository.save(AgentTaskRecord(
        id=agent_task_id, workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.CLAIMED, task_version=1, worker_id="worker-1",
        lease_token=claim_token, lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return (
        service, agent_task_id, claim_token, outbox_repository, workflow_instance_repository, workflow_instance_id,
        checkpoint_repository, agent_task_repository,
    )


def test_completing_with_a_result_payload_publishes_a_completed_event(wiring) -> None:
    service, agent_task_id, claim_token, outbox_repository, *_ = wiring

    view = service.complete(CompleteAgentTaskCommand(
        agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, result_payload="diagnostics collected"
    ))

    assert view.state is AgentTaskState.COMPLETED
    assert view.workflow_version == 1
    assert len(outbox_repository.recorded()) == 1
    assert outbox_repository.recorded()[0].event_type == "agent_runtime.agent_task.completed"


def test_completing_with_a_failure_reason_publishes_a_failed_event(wiring) -> None:
    service, agent_task_id, claim_token, outbox_repository, *_ = wiring

    view = service.complete(CompleteAgentTaskCommand(
        agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, failure_reason="tool timed out"
    ))

    assert view.state is AgentTaskState.FAILED_FINAL
    assert outbox_repository.recorded()[0].event_type == "agent_runtime.agent_task.failed"


def test_completing_an_unknown_task_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(AgentTaskNotFoundException):
        service.complete(CompleteAgentTaskCommand(
            AgentTaskId.new_id(), LeaseToken.new_token(), IdempotencyKey("complete-x"), workflow_version=1, result_payload="ok"
        ))


def test_completing_an_already_completed_task_is_rejected(wiring) -> None:
    service, agent_task_id, claim_token, *_ = wiring
    service.complete(CompleteAgentTaskCommand(
        agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, result_payload="diagnostics collected"
    ))

    with pytest.raises(InvalidAgentTaskTransitionException):
        service.complete(CompleteAgentTaskCommand(
            agent_task_id, claim_token, IdempotencyKey("complete-2"), workflow_version=1, result_payload="again"
        ))


def test_completing_with_the_wrong_claim_token_is_rejected(wiring) -> None:
    """09-concurrency-and-idempotency §"Task Claim": "Worker completion must submit claimToken. Mismatch is rejected."."""
    service, agent_task_id, *_ = wiring

    with pytest.raises(ClaimTokenMismatchException):
        service.complete(CompleteAgentTaskCommand(
            agent_task_id, LeaseToken.new_token(), IdempotencyKey("complete-1"), workflow_version=1, result_payload="ok"
        ))


def test_completing_with_a_stale_workflow_version_is_rejected(wiring) -> None:
    """SPEC-ARO-009 09-concurrency-and-idempotency §"Workflow Version": "Task worker
    receives workflowVersion when reading a task and must validate it on result
    submission" — catches staleness workflow-wide (e.g. an admin force-complete/fail/
    cancel), not only the pause/resume-specific case StalePauseGenerationException covers.
    """
    service, agent_task_id, claim_token, _, workflow_instance_repository, workflow_instance_id, *_ = wiring
    current = workflow_instance_repository.find_by_id(workflow_instance_id)
    workflow_instance_repository.save(dataclasses.replace(current, workflow_version=2))

    with pytest.raises(StaleWorkflowVersionException):
        service.complete(CompleteAgentTaskCommand(
            agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, result_payload="ok"
        ))


def test_completing_after_the_workflow_paused_and_resumed_is_rejected_as_stale(wiring) -> None:
    """09-concurrency-and-idempotency §"Workflow Version": "For pause/resume, it must
    also validate pauseGeneration." Submits the workflow's new (post-mutation)
    workflow_version so that check passes cleanly and this test exercises
    StalePauseGenerationException specifically, not StaleWorkflowVersionException.
    """
    service, agent_task_id, claim_token, _, workflow_instance_repository, workflow_instance_id, *_ = wiring
    current = workflow_instance_repository.find_by_id(workflow_instance_id)
    workflow_instance_repository.save(dataclasses.replace(current, pause_generation=1, workflow_version=2))

    with pytest.raises(StalePauseGenerationException):
        service.complete(CompleteAgentTaskCommand(
            agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=2, result_payload="ok"
        ))


def test_completing_writes_an_after_task_checkpoint(wiring) -> None:
    """SPEC-ARO-008 04-use-cases UC-02 step 5: "After task completion, write AFTER_TASK
    checkpoint."
    """
    service, agent_task_id, claim_token, _, _, workflow_instance_id, checkpoint_repository, _ = wiring

    service.complete(CompleteAgentTaskCommand(
        agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, result_payload="diagnostics collected"
    ))

    checkpoints = checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert [c.type for c in checkpoints] == [CheckpointType.AFTER_TASK]


def test_completing_unlocks_a_downstream_task_whose_only_dependency_just_completed(wiring) -> None:
    """SPEC-ARO-008 04-use-cases UC-02 step 6: "Coordinator unlocks downstream Agent Tasks
    when dependencies are satisfied" — proven through the real command, not by calling
    CoordinateAgentTasksService directly.
    """
    service, agent_task_id, claim_token, _, _, workflow_instance_id, checkpoint_repository, agent_task_repository = wiring
    graph = TaskGraph((
        TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("remediate", "apply_fix", frozenset({"collect"}), JoinPolicy.ALL_SUCCESS),
    ))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)
    checkpoint_repository.save(CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload=task_graph_codec.encode(definition), recorded_at=agent_task_repository.find_by_id(agent_task_id).created_at,
    ))

    service.complete(CompleteAgentTaskCommand(
        agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, result_payload="diagnostics collected"
    ))

    tasks = agent_task_repository.find_by_workflow_instance_id(workflow_instance_id)
    remediate = next(task for task in tasks if task.task_key == "remediate")
    assert remediate.state is AgentTaskState.READY


def test_completing_with_a_failure_does_not_unlock_a_downstream_task(wiring) -> None:
    """A downstream task depending on a FAILED_FINAL predecessor never becomes runnable —
    domain.coordinator.runnable_task_keys only counts COMPLETED dependencies. Since
    "remediate" is therefore never materialized, and "collect" (the only materialized
    task) is terminal, the task graph is settled with no successful outcome — SPEC-ARO-010
    08-transaction-and-outbox §"Task Complete Transaction" step 6 drives the workflow
    straight to FAILED rather than leaving it stuck RUNNING forever.
    """
    service, agent_task_id, claim_token, _, _, workflow_instance_id, checkpoint_repository, agent_task_repository = wiring
    graph = TaskGraph((
        TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("remediate", "apply_fix", frozenset({"collect"}), JoinPolicy.ALL_SUCCESS),
    ))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)
    checkpoint_repository.save(CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload=task_graph_codec.encode(definition), recorded_at=agent_task_repository.find_by_id(agent_task_id).created_at,
    ))

    service.complete(CompleteAgentTaskCommand(
        agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, failure_reason="boom"
    ))

    tasks = agent_task_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert [task.task_key for task in tasks] == ["collect"]

    workflow_instance_repository = wiring[4]
    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.FAILED
    assert workflow.workflow_version == 2


class _RaisingCompleteWorkflowService:
    """Stands in for a concurrently-completing sibling task winning the race to settle the
    workflow first: whichever CompleteAgentTaskService instance loses must swallow the
    conflict rather than fail the task completion its own caller is waiting on.
    """

    def complete(self, command):  # noqa: ANN001, ANN201 - test double, mirrors CompleteWorkflowService.complete's shape
        raise WorkflowInstanceVersionConflictException()


def test_a_concurrent_settlement_race_does_not_fail_the_tasks_own_completion(wiring) -> None:
    """SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6: two
    sibling tasks completing near-simultaneously can both observe "the graph is settled"
    and both attempt the auto-transition; only one wins the optimistic-version race. The
    loser's own task completion must still be reported as successful — the race was lost on
    a best-effort follow-up, not on the thing the caller actually asked for.
    """
    (
        service, agent_task_id, claim_token, outbox_repository, workflow_instance_repository,
        workflow_instance_id, checkpoint_repository, agent_task_repository,
    ) = wiring
    graph = TaskGraph((TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),))
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)
    checkpoint_repository.save(CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload=task_graph_codec.encode(definition), recorded_at=agent_task_repository.find_by_id(agent_task_id).created_at,
    ))
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    racing_service = CompleteAgentTaskService(
        agent_task_repository, workflow_instance_repository, checkpoint_repository, outbox_repository,
        InMemoryCommandIdempotencyRepository(), FakeClock(), coordinate_agent_tasks_service,
        _RaisingCompleteWorkflowService(), FailWorkflowService(
            workflow_instance_repository, outbox_repository, InMemoryCommandIdempotencyRepository(), FakeClock()
        ),
    )

    view = racing_service.complete(CompleteAgentTaskCommand(
        agent_task_id, claim_token, IdempotencyKey("complete-1"), workflow_version=1, result_payload="diagnostics collected"
    ))

    assert view.state is AgentTaskState.COMPLETED
    # the workflow itself is left untouched — the (simulated) sibling won the transition
    assert workflow_instance_repository.find_by_id(workflow_instance_id).state is WorkflowState.RUNNING
