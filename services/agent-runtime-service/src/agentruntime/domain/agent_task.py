"""Domain aggregate for Agent Task state (13-package-and-class-design §"Domain
Layer"). 02-business-invariants §"Agent Task Invariants": "An Agent Task must
belong to one Workflow Instance" and "cannot be reused across Workflow
Instances" — every function below is scoped to a single workflow_instance_id,
never re-parented.
"""

from __future__ import annotations

from datetime import datetime

from agentruntime.domain.enums import AgentTaskState, WorkflowState
from agentruntime.domain.events import (
    AgentTaskClaimed,
    AgentTaskCompleted,
    AgentTaskCreated,
    AgentTaskFailed,
    AgentTaskRetried,
    AgentTaskStaled,
    AgentTaskWaitingForTool,
)
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


def wait_for_tool(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    current_version: int,
    occurred_at: datetime,
) -> AgentTaskWaitingForTool:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 4: "Set
    task to WAITING_TOOL." Reuses complete()/fail()'s own _require_active_claim() guard:
    only a task still actively claimed (CLAIMED/RUNNING) — i.e. the same task whose
    claimToken RequestToolService just validated — can enter this wait.
    """
    _require_active_claim(current_state)

    return AgentTaskWaitingForTool(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.WAITING_TOOL,
        task_version=current_version + 1,
        occurred_at=occurred_at,
    )


def complete_from_tool_result(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    current_version: int,
    result_payload: str,
    occurred_at: datetime,
) -> AgentTaskCompleted:
    """SPEC-ARO-020 04-use-cases UC-04 "消费 tool.completed" step 5: a tool.completed.v1
    delivery (status COMPLETED) completes the Agent Task that was WAITING_TOOL for it —
    the counterpart to wait_for_tool(). Produces the same AgentTaskCompleted event type
    complete() does (same downstream checkpoint/outbox/settlement handling), but with a
    deliberately different source-state guard: only WAITING_TOOL, never CLAIMED/RUNNING
    directly. This is intentionally *not* a broadened complete() — SPEC-ARO-019 requires a
    worker to no longer be able to self-report completion once WAITING_TOOL (02-business-
    invariants: "Tool result 必须通过 tool.completed 或 tool.failed 回到 Runtime"), so that
    guard must stay exactly as strict as it is; this is a separate, narrower entry point.
    """
    if not result_payload or not result_payload.strip():
        raise ValueError("result_payload must not be blank")
    if current_state is not AgentTaskState.WAITING_TOOL:
        raise InvalidAgentTaskTransitionException(current_state, "WAITING_TOOL")

    return AgentTaskCompleted(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.COMPLETED,
        task_version=current_version + 1,
        occurred_at=occurred_at,
        result_payload=result_payload,
    )


def fail_from_tool_result(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    current_version: int,
    failure_reason: str,
    occurred_at: datetime,
) -> AgentTaskFailed:
    """SPEC-ARO-020: the failure counterpart to complete_from_tool_result() — a
    tool.completed.v1 delivery carrying status FAILED. Always produces FAILED_FINAL,
    mirroring fail()'s own current scope (retryability is SPEC-ARO-010's deferred
    territory, unchanged here).
    """
    if not failure_reason or not failure_reason.strip():
        raise ValueError("failure_reason must not be blank")
    if current_state is not AgentTaskState.WAITING_TOOL:
        raise InvalidAgentTaskTransitionException(current_state, "WAITING_TOOL")

    return AgentTaskFailed(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.FAILED_FINAL,
        task_version=current_version + 1,
        occurred_at=occurred_at,
        failure_reason=failure_reason,
    )


def mark_stale(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    current_version: int,
    reason: str,
    occurred_at: datetime,
) -> AgentTaskStaled:
    """SPEC-ARO-016 (Stale Generation Worker Result): only a task still actively claimed
    (CLAIMED/RUNNING) can go STALE — reuses complete()/fail()'s own
    _require_active_claim() so a task that already reached a terminal state (or was
    already marked STALE) is protected the exact same way: an out-of-date worker result
    arriving after the fact must never overwrite a legitimate outcome that already landed.
    AgentTaskState.is_claimable() already includes STALE alongside CLAIMED/RUNNING, so the
    "path back to claimable" this state needs is already there (domain.agent_task.claim())
    — this function only closes the other half, persisting the outcome itself.
    """
    if not reason or not reason.strip():
        raise ValueError("reason must not be blank")
    _require_active_claim(current_state)

    return AgentTaskStaled(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.STALE,
        task_version=current_version + 1,
        occurred_at=occurred_at,
        reason=reason,
    )


def retry_after_lease_expiry(
    agent_task_id: AgentTaskId,
    workflow_instance_id: WorkflowInstanceId,
    current_state: AgentTaskState,
    current_version: int,
    current_attempt: int,
    max_attempts: int,
    occurred_at: datetime,
) -> AgentTaskRetried:
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5: "对 CLAIMED/RUNNING
    且 lease 过期的 task 做 retry 或 stale 标记" — the retry half of that step; mark_stale()
    (already shipped in SPEC-ARO-016) is the other, reused unchanged for the
    attempt-budget-exhausted case per the resolution of this domain's own conflicting
    10-failure-handling text (confirmed with the user): the more specific recovery-worker
    step list, not the separate "Retry Policy" section's FAILED_FINAL, governs this
    specific lease-expiry scenario.

    Reuses complete()/fail()/mark_stale()'s own _require_active_claim() guard — only a
    task still actively claimed (CLAIMED/RUNNING) whose lease the caller has already
    confirmed is expired may be retried this way. attempt < max_attempts is this
    function's own precondition, checked defensively; the caller
    (RecoverExpiredLeaseTasksService) is what actually decides retry vs mark_stale() per
    task before ever reaching here.
    """
    _require_active_claim(current_state)
    if current_attempt >= max_attempts:
        raise InvalidAgentTaskTransitionException(current_state, "attempt < max_attempts")

    return AgentTaskRetried(
        agent_task_id=agent_task_id,
        workflow_instance_id=workflow_instance_id,
        to_state=AgentTaskState.READY,
        task_version=current_version + 1,
        occurred_at=occurred_at,
        attempt=current_attempt + 1,
    )


def _require_active_claim(current_state: AgentTaskState) -> None:
    if current_state not in (AgentTaskState.CLAIMED, AgentTaskState.RUNNING):
        raise InvalidAgentTaskTransitionException(current_state, "CLAIMED or RUNNING")
