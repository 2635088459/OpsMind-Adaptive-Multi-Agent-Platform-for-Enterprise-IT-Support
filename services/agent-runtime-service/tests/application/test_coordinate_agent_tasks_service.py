from __future__ import annotations

import dataclasses

import pytest

from agentruntime.application.records import CheckpointRecord
from agentruntime.application.services import task_graph_codec
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.domain.enums import AgentTaskState, CheckpointType, JoinPolicy, WorkflowState
from agentruntime.domain.ids import CheckpointId, DefinitionVersion, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.domain.task_graph import TaskGraph, TaskNode, WorkflowDefinition
from agentruntime.infrastructure.persistence.in_memory import InMemoryAgentTaskRepository, InMemoryCheckpointRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    clock = FakeClock()
    workflow_instance_id = WorkflowInstanceId.new_id()
    graph = TaskGraph((
        TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("remediate", "apply_fix", frozenset({"collect"}), JoinPolicy.ALL_SUCCESS),
    ))
    return service, agent_task_repository, workflow_instance_id, graph, clock, checkpoint_repository


def _save_started_checkpoint(checkpoint_repository, workflow_instance_id: WorkflowInstanceId, graph: TaskGraph, now) -> None:
    definition = WorkflowDefinition(WorkflowDefinitionId("triage-v1"), DefinitionVersion(1), WorkflowType("TICKET_TRIAGE"), graph)
    checkpoint_repository.save(CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload=task_graph_codec.encode(definition), recorded_at=now,
    ))


def test_materializing_only_creates_the_currently_runnable_wave(wiring) -> None:
    service, agent_task_repository, workflow_instance_id, graph, clock, _ = wiring

    materialized = service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, clock.now())

    assert [task.task_key for task in materialized] == ["collect"]
    assert len(agent_task_repository.find_by_workflow_instance_id(workflow_instance_id)) == 1


def test_a_materialized_task_starts_in_the_ready_state_not_pending(wiring) -> None:
    """SPEC-ARO-007: every node reaching this path already had its dependencies confirmed
    satisfied by domain.coordinator.runnable_task_keys, so it must be immediately
    claimable (READY), never the still-blocked PENDING.
    """
    service, _, workflow_instance_id, graph, clock, _cp = wiring

    [collect_task] = service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, clock.now())

    assert collect_task.state is AgentTaskState.READY


def test_materializing_again_after_the_dependency_completes_creates_the_downstream_task(wiring) -> None:
    service, agent_task_repository, workflow_instance_id, graph, clock, _ = wiring
    first_wave = service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, clock.now())
    collect_task = first_wave[0]

    now = clock.now()
    agent_task_repository.save(dataclasses.replace(
        collect_task, state=AgentTaskState.COMPLETED, task_version=collect_task.task_version + 1,
        result_payload="done", updated_at=now,
    ))

    second_wave = service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, now)

    assert [task.task_key for task in second_wave] == ["remediate"]
    assert second_wave[0].state is AgentTaskState.READY
    assert second_wave[0].depends_on_task_keys == frozenset({"collect"})


def test_materializing_under_a_terminal_workflow_is_rejected(wiring) -> None:
    service, _, workflow_instance_id, graph, clock, _cp = wiring

    with pytest.raises(RuntimeError):
        service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.COMPLETED, graph, clock.now())


def test_unlock_downstream_tasks_re_derives_the_graph_from_the_started_checkpoint(wiring) -> None:
    """SPEC-ARO-008 04-use-cases UC-02 step 6: with no Workflow Definition catalog table,
    the STARTED checkpoint is the only durable record of the graph shape — this proves
    unlock_downstream_tasks can find and decode it, then materialize what it unlocks.
    """
    service, agent_task_repository, workflow_instance_id, graph, clock, checkpoint_repository = wiring
    now = clock.now()
    _save_started_checkpoint(checkpoint_repository, workflow_instance_id, graph, now)
    [collect_task] = service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, now)
    agent_task_repository.save(dataclasses.replace(
        collect_task, state=AgentTaskState.COMPLETED, task_version=collect_task.task_version + 1,
        result_payload="done", updated_at=now,
    ))

    unlocked = service.unlock_downstream_tasks(workflow_instance_id, WorkflowState.RUNNING, now)

    assert [task.task_key for task in unlocked] == ["remediate"]
    assert unlocked[0].state is AgentTaskState.READY


def test_unlock_downstream_tasks_is_a_no_op_under_a_terminal_workflow(wiring) -> None:
    """Guards against blowing up if the workflow reached a terminal state (e.g. an admin
    force-complete/fail/cancel) concurrently with the task completion that triggered this
    call — materialize_runnable_tasks would otherwise raise.
    """
    service, _, workflow_instance_id, graph, clock, checkpoint_repository = wiring
    now = clock.now()
    _save_started_checkpoint(checkpoint_repository, workflow_instance_id, graph, now)

    unlocked = service.unlock_downstream_tasks(workflow_instance_id, WorkflowState.COMPLETED, now)

    assert unlocked == []


def test_unlock_downstream_tasks_is_a_no_op_when_no_started_checkpoint_exists(wiring) -> None:
    service, _, workflow_instance_id, _graph, clock, _checkpoint_repository = wiring

    unlocked = service.unlock_downstream_tasks(workflow_instance_id, WorkflowState.RUNNING, clock.now())

    assert unlocked == []


def test_determine_settlement_is_none_when_no_started_checkpoint_exists(wiring) -> None:
    service, _, workflow_instance_id, _graph, _clock, _checkpoint_repository = wiring

    assert service.determine_settlement(workflow_instance_id) is None


def test_determine_settlement_is_none_while_the_graph_still_has_runnable_or_in_flight_work(wiring) -> None:
    service, agent_task_repository, workflow_instance_id, graph, clock, checkpoint_repository = wiring
    now = clock.now()
    _save_started_checkpoint(checkpoint_repository, workflow_instance_id, graph, now)
    service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, now)

    assert service.determine_settlement(workflow_instance_id) is None


def test_determine_settlement_is_completed_once_every_task_in_the_graph_succeeds(wiring) -> None:
    """SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6."""
    service, agent_task_repository, workflow_instance_id, graph, clock, checkpoint_repository = wiring
    now = clock.now()
    _save_started_checkpoint(checkpoint_repository, workflow_instance_id, graph, now)
    [collect_task] = service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, now)
    agent_task_repository.save(dataclasses.replace(
        collect_task, state=AgentTaskState.COMPLETED, task_version=collect_task.task_version + 1,
        result_payload="done", updated_at=now,
    ))
    [remediate_task] = service.unlock_downstream_tasks(workflow_instance_id, WorkflowState.RUNNING, now)

    assert service.determine_settlement(workflow_instance_id) is None  # remediate is READY, not terminal yet

    agent_task_repository.save(dataclasses.replace(
        remediate_task, state=AgentTaskState.COMPLETED, task_version=remediate_task.task_version + 1,
        result_payload="done", updated_at=now,
    ))
    service.unlock_downstream_tasks(workflow_instance_id, WorkflowState.RUNNING, now)  # nothing left to unlock

    assert service.determine_settlement(workflow_instance_id) is WorkflowState.COMPLETED


def test_determine_settlement_is_failed_once_a_task_failure_permanently_blocks_the_rest_of_the_graph(wiring) -> None:
    service, agent_task_repository, workflow_instance_id, graph, clock, checkpoint_repository = wiring
    now = clock.now()
    _save_started_checkpoint(checkpoint_repository, workflow_instance_id, graph, now)
    [collect_task] = service.materialize_runnable_tasks(workflow_instance_id, WorkflowState.RUNNING, graph, now)
    agent_task_repository.save(dataclasses.replace(
        collect_task, state=AgentTaskState.FAILED_FINAL, task_version=collect_task.task_version + 1,
        failure_reason="boom", updated_at=now,
    ))
    service.unlock_downstream_tasks(workflow_instance_id, WorkflowState.RUNNING, now)  # remediate stays blocked

    assert service.determine_settlement(workflow_instance_id) is WorkflowState.FAILED
