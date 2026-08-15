"""Domain aggregate root for Agent Workflow state (13-package-and-class-design
§"Domain Layer"). Mirrors this codebase's guard-projection pattern (established
by the sibling Ticket Workflow service): write operations never rehydrate a
full instance from storage, they validate the caller-supplied current
state/version and return the event the caller persists. This module expresses
rules only — it must never depend on FastAPI, SQLAlchemy, a broker client, or
a Tool SDK (02-business-invariants §"Tool Gateway Boundary"; SPEC-ARO-001
domain-rules).
"""

from __future__ import annotations

from datetime import datetime

from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.events import (
    WorkflowCancelled,
    WorkflowCompleted,
    WorkflowFailed,
    WorkflowPaused,
    WorkflowResumed,
    WorkflowStarted,
    WorkflowWaitingForTool,
    WorkflowWokenFromApprovalWait,
    WorkflowWokenFromToolWait,
    WorkflowWokenFromVerificationWait,
)
from agentruntime.domain.exceptions import InvalidWorkflowStateException, InvalidWorkflowTransitionException
from agentruntime.domain.ids import (
    DefinitionVersion,
    IdempotencyKey,
    TicketCycleId,
    TicketId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)

_PAUSABLE_STATES = frozenset({
    WorkflowState.RUNNING, WorkflowState.WAITING_FOR_APPROVAL, WorkflowState.WAITING_FOR_TOOL,
    WorkflowState.WAITING_FOR_VERIFICATION, WorkflowState.WAITING_FOR_INPUT,
})
"""03-state-machine: pause must be able to freeze a workflow that is currently blocked on an
external wake-up, not only one that is actively RUNNING — a paused instance's outstanding
wait is what CompleteAgentTaskService/the future event consumers must refuse to resume until
the matching resume() call restores RUNNING.
"""

_NON_TERMINAL_STATES = frozenset(
    {WorkflowState.CREATED, WorkflowState.RUNNING, WorkflowState.PAUSED}
    | {WorkflowState.WAITING_FOR_APPROVAL, WorkflowState.WAITING_FOR_TOOL, WorkflowState.WAITING_FOR_VERIFICATION, WorkflowState.WAITING_FOR_INPUT}
)


def start(
    workflow_instance_id: WorkflowInstanceId,
    ticket_id: TicketId,
    ticket_cycle_id: TicketCycleId,
    workflow_type: WorkflowType,
    definition_id: WorkflowDefinitionId,
    definition_version: DefinitionVersion,
    occurred_at: datetime,
) -> WorkflowStarted:
    """02-business-invariants §"Workflow Instance Invariants": at most one active instance may
    exist for the same ticketId + ticketCycleId + workflowType. That uniqueness check requires
    a repository read and belongs to the Application layer (StartWorkflowService); this pure
    function only produces the initial event once the caller has already confirmed no
    conflicting active instance exists.
    """
    return WorkflowStarted(
        workflow_instance_id=workflow_instance_id,
        from_state=None,
        to_state=WorkflowState.RUNNING,
        workflow_version=1,
        occurred_at=occurred_at,
        ticket_id=ticket_id,
        ticket_cycle_id=ticket_cycle_id,
        workflow_type=workflow_type,
        definition_id=definition_id,
        definition_version=definition_version,
    )


def pause(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    current_pause_generation: int,
    idempotency_key: IdempotencyKey,
    occurred_at: datetime,
) -> WorkflowPaused:
    """02-business-invariants §"Pause / Resume Idempotency Invariants": the caller must have
    already resolved that idempotency_key has not been used before invoking this function — a
    duplicate pause is served from the previously stored result and never re-enters this
    transition (so workflow.paused is never published twice for the same pause).
    """
    if current_state not in _PAUSABLE_STATES:
        raise InvalidWorkflowStateException(current_state, _PAUSABLE_STATES)

    return WorkflowPaused(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.PAUSED,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
        pause_generation=current_pause_generation + 1,
        idempotency_key=idempotency_key,
    )


def resume(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    current_pause_generation: int,
    idempotency_key: IdempotencyKey,
    occurred_at: datetime,
) -> WorkflowResumed:
    """Mirrors pause(): duplicate-resume detection is the caller's responsibility."""
    if current_state is not WorkflowState.PAUSED:
        raise InvalidWorkflowTransitionException(current_state, WorkflowState.PAUSED)

    return WorkflowResumed(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.RUNNING,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
        pause_generation=current_pause_generation,
        idempotency_key=idempotency_key,
    )


def wait_for_tool(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    occurred_at: datetime,
) -> WorkflowWaitingForTool:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 5: "Set
    workflow to WAITING_FOR_TOOL." Only a RUNNING workflow may enter this wait — a
    request-tool call is only reachable from an actively-claimed task in the first
    place (RequestToolService's own claimToken check), and a task can only be claimed
    while its workflow is RUNNING, so any other current_state here means something else
    already moved the workflow (e.g. an admin pause/cancel racing the same request) and
    this transition must not silently paper over that.
    """
    if current_state is not WorkflowState.RUNNING:
        raise InvalidWorkflowTransitionException(current_state, WorkflowState.WAITING_FOR_TOOL)

    return WorkflowWaitingForTool(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.WAITING_FOR_TOOL,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
    )


def wake_from_tool_wait(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    occurred_at: datetime,
) -> WorkflowWokenFromToolWait:
    """SPEC-ARO-020 04-use-cases UC-04 "消费 tool.completed": a tool.completed.v1 delivery
    wakes a WAITING_FOR_TOOL workflow back to RUNNING — the counterpart to
    wait_for_tool(). Only WAITING_FOR_TOOL may wake this way; a workflow that moved on
    some other way in the meantime (e.g. an admin pause/cancel racing the same event)
    must not be silently forced back to RUNNING.
    """
    if current_state is not WorkflowState.WAITING_FOR_TOOL:
        raise InvalidWorkflowTransitionException(current_state, WorkflowState.RUNNING)

    return WorkflowWokenFromToolWait(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.RUNNING,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
    )


def wake_from_approval_wait(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    occurred_at: datetime,
) -> WorkflowWokenFromApprovalWait:
    """SPEC-ARO-021 04-use-cases UC-03 "消费 approval.granted": an approval.granted.v1
    delivery whose decision is APPROVED wakes a WAITING_FOR_APPROVAL workflow back to
    RUNNING. Only WAITING_FOR_APPROVAL may wake this way — mirrors
    wake_from_tool_wait()'s own reasoning: a workflow that moved on some other way in the
    meantime (e.g. an admin pause/cancel, or a prior delivery of this same event) must
    not be silently forced back to RUNNING.
    """
    if current_state is not WorkflowState.WAITING_FOR_APPROVAL:
        raise InvalidWorkflowTransitionException(current_state, WorkflowState.RUNNING)

    return WorkflowWokenFromApprovalWait(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.RUNNING,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
    )


def wake_from_verification_wait(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    occurred_at: datetime,
) -> WorkflowWokenFromVerificationWait:
    """SPEC-ARO-022 04-use-cases UC-05 "消费 verification.completed": a
    verification.completed.v1 delivery with passed == True wakes a
    WAITING_FOR_VERIFICATION workflow back to RUNNING — mirrors wake_from_approval_wait()/
    wake_from_tool_wait() exactly. Only WAITING_FOR_VERIFICATION may wake this way.
    """
    if current_state is not WorkflowState.WAITING_FOR_VERIFICATION:
        raise InvalidWorkflowTransitionException(current_state, WorkflowState.RUNNING)

    return WorkflowWokenFromVerificationWait(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.RUNNING,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
    )


def complete(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    occurred_at: datetime,
) -> WorkflowCompleted:
    """Whether every runnable task has reached a terminal outcome under its join policy is a
    task-graph-level question the coordinator module answers; this function only re-asserts the
    instance-level guard once the Application layer has already decided completion is due.
    """
    if current_state.is_terminal():
        raise InvalidWorkflowStateException(current_state, _NON_TERMINAL_STATES - {WorkflowState.CREATED})

    return WorkflowCompleted(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.COMPLETED,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
    )


def fail(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    failure_reason: str,
    occurred_at: datetime,
) -> WorkflowFailed:
    """02-business-invariants: failure paths must retain an auditable reason."""
    if not failure_reason or not failure_reason.strip():
        raise ValueError("failure_reason must not be blank")
    if current_state.is_terminal():
        raise InvalidWorkflowStateException(current_state, _NON_TERMINAL_STATES)

    return WorkflowFailed(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.FAILED,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
        failure_reason=failure_reason,
    )


def cancel(
    workflow_instance_id: WorkflowInstanceId,
    current_state: WorkflowState,
    current_version: int,
    reason: str,
    occurred_at: datetime,
) -> WorkflowCancelled:
    if not reason or not reason.strip():
        raise ValueError("reason must not be blank")
    if current_state.is_terminal():
        raise InvalidWorkflowStateException(current_state, _NON_TERMINAL_STATES)

    return WorkflowCancelled(
        workflow_instance_id=workflow_instance_id,
        from_state=current_state,
        to_state=WorkflowState.CANCELLED,
        workflow_version=current_version + 1,
        occurred_at=occurred_at,
        reason=reason,
    )
