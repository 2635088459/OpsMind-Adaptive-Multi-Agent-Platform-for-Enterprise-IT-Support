"""Domain aggregate for Agent Task state (13-package-and-class-design §"Domain
Layer"). 02-business-invariants §"Agent Task Invariants": "An Agent Task must
belong to one Workflow Instance" and "cannot be reused across Workflow
Instances" — every function below is scoped to a single workflow_instance_id,
never re-parented.
"""

from __future__ import annotations

from datetime import datetime

from agentruntime.domain.enums import AgentTaskState, WorkflowState
from agentruntime.domain.events import AgentTaskClaimed, AgentTaskCompleted, AgentTaskCreated, AgentTaskFailed
from agentruntime.domain.exceptions import (
    AgentTaskAlreadyClaimedException,
    AgentTaskDependencyNotSatisfiedException,
    InvalidAgentTaskTransitionException,
)
from agentruntime.domain.ids import AgentTaskId, LeaseToken, WorkflowInstanceId


def create(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_workflow_state: WorkflowState,
    task_type: str,
    depends_on_task_keys: frozenset[str],
    is_ready: bool,
    occurred_at: datetime,
    agent_role: str | None = None,
) -> AgentTaskCreated:
    """02-business-invariants §"Workflow Instance Invariants": "After a terminal state, no new
    Agent Task may be created unless a new ticket cycle creates a new Workflow Instance" —
    current_workflow_state is supplied by the caller (the Application layer already loaded the
    owning Workflow Instance) since this pure function must not query it itself.

    03-state-machine: is_ready distinguishes PENDING ("waiting for dependencies") from
    READY ("dependencies are satisfied and task can be claimed") — this pure function has
    no repository access to check sibling task states itself, so the caller (today,
    CoordinateAgentTasksService, which only ever calls this for graph nodes
    domain.coordinator.runnable_task_keys already confirmed are runnable) must supply the
    answer.
    """
    if not task_type or not task_type.strip():
        raise ValueError("task_type must not be blank")
    if current_workflow_state.is_terminal():
        raise RuntimeError("cannot create an agent task under a terminal workflow instance")

    return AgentTaskCreated(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.READY if is_ready else AgentTaskState.PENDING,
        task_version=1,
        occurred_at=occurred_at,
        task_type=task_type,
        depends_on_task_keys=frozenset(depends_on_task_keys),
        agent_role=agent_role,
    )


def claim(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    all_dependencies_completed: bool,
    current_lease_expires_at: datetime | None,
    current_version: int,
    worker_id: str,
    lease_token: LeaseToken,
    lease_expires_at: datetime,
    occurred_at: datetime,
) -> AgentTaskClaimed:
    """02-business-invariants §"Agent Task Invariants" + §"Multi-Agent Orchestration
    Invariants": all dependsOn tasks must be complete, and claim is lease-based — a task whose
    current lease has not yet expired cannot be reclaimed by a different worker.
    """
    if not worker_id or not worker_id.strip():
        raise ValueError("worker_id must not be blank")
    if lease_expires_at <= occurred_at:
        raise ValueError("lease_expires_at must be after occurred_at")
    if not all_dependencies_completed:
        raise AgentTaskDependencyNotSatisfiedException()
    if not current_state.is_claimable():
        raise InvalidAgentTaskTransitionException(current_state, "READY, or CLAIMED/RUNNING/STALE with an expired lease")

    already_leased = current_state is not AgentTaskState.READY
    if already_leased and current_lease_expires_at is not None and occurred_at < current_lease_expires_at:
        raise AgentTaskAlreadyClaimedException()

    return AgentTaskClaimed(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.CLAIMED,
        task_version=current_version + 1,
        occurred_at=occurred_at,
        worker_id=worker_id,
        lease_token=lease_token,
        lease_expires_at=lease_expires_at,
    )


def complete(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    current_version: int,
    result_payload: str,
    occurred_at: datetime,
) -> AgentTaskCompleted:
    """02-business-invariants §"Agent Task Invariants": "Task completion must write either a
    result payload or an explicit failure reason" and "may be published once" — enforced by
    requiring the task still be in a non-terminal, claimed-or-running state.
    """
    if not result_payload or not result_payload.strip():
        raise ValueError("result_payload must not be blank")
    _require_active_claim(current_state)

    return AgentTaskCompleted(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.COMPLETED,
        task_version=current_version + 1,
        occurred_at=occurred_at,
        result_payload=result_payload,
    )


def fail(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    current_version: int,
    failure_reason: str,
    occurred_at: datetime,
) -> AgentTaskFailed:
    """Always produces FAILED_FINAL. 03-state-machine also defines FAILED_RETRYABLE, but
    deciding retryability (attempt vs maxAttempts) and the path back to READY is
    SPEC-ARO-010's job (Task Completion and Join Policy, 02-business-invariants) — this
    pure function has no retry policy to consult yet.
    """
    if not failure_reason or not failure_reason.strip():
        raise ValueError("failure_reason must not be blank")
    _require_active_claim(current_state)

    return AgentTaskFailed(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.FAILED_FINAL,
        task_version=current_version + 1,
        occurred_at=occurred_at,
        failure_reason=failure_reason,
    )


def _require_active_claim(current_state: AgentTaskState) -> None:
    if current_state not in (AgentTaskState.CLAIMED, AgentTaskState.RUNNING):
        raise InvalidAgentTaskTransitionException(current_state, "CLAIMED or RUNNING")
