"""13-package-and-class-design §"Application Layer": CoordinateAgentTasksService. Wraps
the stateless agentruntime.domain.coordinator functions with the I/O
(AgentTaskRepository reads/writes) a pure domain function must not perform. Called by
StartWorkflowService to materialize the first runnable wave of tasks, and by
CompleteAgentTaskService (SPEC-ARO-008 04-use-cases UC-02 step 6) after each task
completion to materialize newly-runnable downstream tasks.
"""

from __future__ import annotations

from datetime import datetime

from agentruntime.application.ports_out import AgentTaskRepository, CheckpointRepository
from agentruntime.application.records import AgentTaskRecord
from agentruntime.application.services import task_graph_codec
from agentruntime.domain import agent_task, coordinator
from agentruntime.domain.enums import AgentTaskState, CheckpointType, WorkflowState
from agentruntime.domain.ids import AgentTaskId, WorkflowInstanceId
from agentruntime.domain.task_graph import TaskGraph


class CoordinateAgentTasksService:
    def __init__(self, agent_task_repository: AgentTaskRepository, checkpoint_repository: CheckpointRepository) -> None:
        self._agent_task_repository = agent_task_repository
        self._checkpoint_repository = checkpoint_repository

    def determine_runnable_task_keys(self, workflow_instance_id: WorkflowInstanceId, graph: TaskGraph) -> frozenset[str]:
        """Computes which of graph's nodes are currently runnable, without persisting anything."""
        return coordinator.runnable_task_keys(graph, self._state_by_task_key(workflow_instance_id))

    def materialize_runnable_tasks(
        self,
        workflow_instance_id: WorkflowInstanceId,
        current_workflow_state: WorkflowState,
        graph: TaskGraph,
        now: datetime,
    ) -> list[AgentTaskRecord]:
        """Creates and persists an AgentTaskRecord for every graph node that is runnable and
        does not already have one. 02-business-invariants §"Workflow Instance Invariants":
        "After a terminal state, no new Agent Task may be created" — current_workflow_state
        gates the whole operation up front.
        """
        state_by_task_key = self._state_by_task_key(workflow_instance_id)
        runnable = coordinator.runnable_task_keys(graph, state_by_task_key)
        nodes_by_key = graph.node_by_key()

        materialized: list[AgentTaskRecord] = []
        for task_key in runnable:
            if task_key in state_by_task_key:
                continue
            node = nodes_by_key[task_key]
            agent_task_id = AgentTaskId.new_id()
            # is_ready=True unconditionally: task_key only ever reaches this loop via
            # runnable_task_keys, which already confirmed every depends_on task_key is
            # COMPLETED (SPEC-ARO-007) — every node materialized on this path is READY,
            # never the still-blocked PENDING.
            event = agent_task.create(
                agent_task_id, workflow_instance_id, current_workflow_state, node.task_type, node.depends_on, True, now,
                node.agent_role,
            )
            record = AgentTaskRecord(
                id=agent_task_id, workflow_instance_id=workflow_instance_id, task_key=node.task_key,
                task_type=node.task_type, depends_on_task_keys=node.depends_on, state=event.to_state,
                task_version=event.task_version, worker_id=None, lease_token=None, lease_expires_at=None,
                result_payload=None, failure_reason=None, pause_generation=0, created_at=now, updated_at=now,
                agent_role=event.agent_role,
            )
            materialized.append(self._agent_task_repository.save(record))
        return materialized

    def unlock_downstream_tasks(
        self, workflow_instance_id: WorkflowInstanceId, current_workflow_state: WorkflowState, now: datetime
    ) -> list[AgentTaskRecord]:
        """SPEC-ARO-008 04-use-cases UC-02 step 6: "Coordinator unlocks downstream Agent
        Tasks when dependencies are satisfied" — called after a task reaches COMPLETED or
        FAILED_FINAL. There is no Workflow Definition catalog table (StartWorkflowCommand's
        own docstring), so the task graph shape is re-derived from the STARTED checkpoint
        StartWorkflowService writes (01-domain-model: the checkpoint payload IS the durable
        planner output) — the only other option, deriving the graph purely from already-
        materialized AgentTaskRecords, can't work: a node blocked on the task that just
        completed was never materialized in the first place, so it wouldn't be in that set.

        A no-op once the workflow is terminal — materialize_runnable_tasks would otherwise
        raise (02-business-invariants: "no new Agent Task past a terminal state"), and a
        task completing concurrently with an admin force-complete/fail/cancel must not
        fail *this* call as a result.
        """
        if current_workflow_state.is_terminal():
            return []

        checkpoint = self._checkpoint_repository.find_latest_by_workflow_instance_id_and_type(
            workflow_instance_id, CheckpointType.STARTED
        )
        if checkpoint is None:
            return []

        graph = task_graph_codec.decode_task_graph(checkpoint.payload)
        return self.materialize_runnable_tasks(workflow_instance_id, current_workflow_state, graph, now)

    def determine_settlement(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowState | None:
        """SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6: "If
        workflow reaches terminal state, update workflow and insert workflow.completed.v1."
        Returns WorkflowState.COMPLETED or WorkflowState.FAILED once the task graph has
        nothing left to do (domain.coordinator.is_workflow_settled()), or None while it's
        still in progress — the caller (CompleteAgentTaskService) only acts on a non-None
        result, so "still in progress" and "no STARTED checkpoint found" (mirrors
        unlock_downstream_tasks()'s own defensive default) both collapse to the same "do
        nothing yet" signal.
        """
        checkpoint = self._checkpoint_repository.find_latest_by_workflow_instance_id_and_type(
            workflow_instance_id, CheckpointType.STARTED
        )
        if checkpoint is None:
            return None

        graph = task_graph_codec.decode_task_graph(checkpoint.payload)
        state_by_task_key = self._state_by_task_key(workflow_instance_id)
        if not coordinator.is_workflow_settled(graph, state_by_task_key):
            return None
        return WorkflowState.COMPLETED if coordinator.workflow_outcome_is_success(graph, state_by_task_key) else WorkflowState.FAILED

    def _state_by_task_key(self, workflow_instance_id: WorkflowInstanceId) -> dict[str, AgentTaskState]:
        return {
            record.task_key: record.state
            for record in self._agent_task_repository.find_by_workflow_instance_id(workflow_instance_id)
        }
