from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from agentruntime.domain import agent_task
from agentruntime.domain.enums import AgentTaskState, WorkflowState
from agentruntime.domain.exceptions import (
    AgentTaskAlreadyClaimedException,
    AgentTaskDependencyNotSatisfiedException,
    InvalidAgentTaskTransitionException,
)
from agentruntime.domain.ids import AgentTaskId, LeaseToken, WorkflowInstanceId

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=UTC)
AGENT_TASK_ID = AgentTaskId.new_id()
WORKFLOW_INSTANCE_ID = WorkflowInstanceId.new_id()


def test_create_under_a_terminal_workflow_is_rejected() -> None:
    with pytest.raises(RuntimeError):
        agent_task.create(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, WorkflowState.COMPLETED, "collect-logs", frozenset(), True, NOW)


def test_create_when_ready_produces_the_ready_state() -> None:
    event = agent_task.create(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, "collect-logs", frozenset(), True, NOW)

    assert event.to_state is AgentTaskState.READY
    assert event.task_version == 1


def test_create_when_not_ready_produces_the_pending_state() -> None:
    """03-state-machine: PENDING is "created and waiting for dependencies" — distinct
    from READY, which domain.coordinator.runnable_task_keys/CoordinateAgentTasksService
    never produces directly today (SPEC-ARO-007).
    """
    event = agent_task.create(
        AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, "apply-fix", frozenset({"collect"}), False, NOW
    )

    assert event.to_state is AgentTaskState.PENDING


def test_create_threads_the_real_dependency_task_keys_onto_the_event() -> None:
    event = agent_task.create(
        AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, "apply-fix", frozenset({"collect"}), False, NOW
    )

    assert event.depends_on_task_keys == frozenset({"collect"})


def test_create_threads_agent_role_onto_the_event() -> None:
    """SPEC-ARO-009 05-api-contracts "Claim Task" / 01-domain-model."""
    event = agent_task.create(
        AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, "collect-logs", frozenset(), True, NOW, "triage_agent"
    )

    assert event.agent_role == "triage_agent"


def test_create_defaults_agent_role_to_none() -> None:
    event = agent_task.create(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, WorkflowState.RUNNING, "collect-logs", frozenset(), True, NOW)

    assert event.agent_role is None


def test_claim_requires_all_dependencies_completed() -> None:
    with pytest.raises(AgentTaskDependencyNotSatisfiedException):
        agent_task.claim(
            AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.PENDING, False, None, 1,
            "worker-1", LeaseToken.new_token(), NOW + timedelta(minutes=5), NOW,
        )


def test_claim_from_pending_even_with_satisfied_dependencies_is_rejected() -> None:
    """SPEC-ARO-007: a task must be promoted to READY before it can be claimed — a
    dependency check passing is not by itself enough, unlike the pre-007 domain model
    where PENDING was directly claimable.
    """
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.claim(
            AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.PENDING, True, None, 1,
            "worker-1", LeaseToken.new_token(), NOW + timedelta(minutes=5), NOW,
        )


def test_claim_from_ready_with_satisfied_dependencies_succeeds() -> None:
    event = agent_task.claim(
        AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.READY, True, None, 1,
        "worker-1", LeaseToken.new_token(), NOW + timedelta(minutes=5), NOW,
    )

    assert event.to_state is AgentTaskState.CLAIMED
    assert event.task_version == 2
    assert event.worker_id == "worker-1"


def test_claim_under_an_unexpired_lease_is_rejected() -> None:
    unexpired_lease = NOW + timedelta(minutes=1)

    with pytest.raises(AgentTaskAlreadyClaimedException):
        agent_task.claim(
            AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, True, unexpired_lease, 2,
            "worker-2", LeaseToken.new_token(), NOW + timedelta(minutes=5), NOW,
        )


def test_claim_after_lease_expiry_is_allowed() -> None:
    expired_lease = NOW - timedelta(seconds=1)

    event = agent_task.claim(
        AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, True, expired_lease, 2,
        "worker-2", LeaseToken.new_token(), NOW + timedelta(minutes=5), NOW,
    )

    assert event.worker_id == "worker-2"
    assert event.task_version == 3


def test_complete_requires_a_claimed_or_running_source_state() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.complete(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.PENDING, 1, "ok", NOW)


def test_complete_from_claimed_produces_result_payload() -> None:
    event = agent_task.complete(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, 2, "diagnostics collected", NOW)

    assert event.to_state is AgentTaskState.COMPLETED
    assert event.result_payload == "diagnostics collected"


def test_fail_from_claimed_produces_failure_reason() -> None:
    event = agent_task.fail(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, 2, "tool timed out", NOW)

    assert event.to_state is AgentTaskState.FAILED_FINAL
    assert event.failure_reason == "tool timed out"


def test_completing_an_already_terminal_task_is_rejected() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.complete(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.COMPLETED, 3, "again", NOW)


@pytest.mark.parametrize("state", [AgentTaskState.CLAIMED, AgentTaskState.RUNNING])
def test_mark_stale_from_an_active_claim_produces_the_stale_state(state: AgentTaskState) -> None:
    """SPEC-ARO-016 (Stale Generation Worker Result)."""
    event = agent_task.mark_stale(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, state, 2, "stale_workflow_version", NOW)

    assert event.to_state is AgentTaskState.STALE
    assert event.task_version == 3
    assert event.reason == "stale_workflow_version"


def test_mark_stale_requires_a_claimed_or_running_source_state() -> None:
    """A task that already reached a terminal state (or was already marked STALE) must
    never have an out-of-date worker result overwrite it.
    """
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.mark_stale(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.COMPLETED, 3, "stale_pause_generation", NOW)


def test_mark_stale_rejects_a_blank_reason() -> None:
    with pytest.raises(ValueError):
        agent_task.mark_stale(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, 2, "   ", NOW)


@pytest.mark.parametrize("state", [AgentTaskState.CLAIMED, AgentTaskState.RUNNING])
def test_retry_after_lease_expiry_from_an_active_claim_produces_the_ready_state(state: AgentTaskState) -> None:
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5."""
    event = agent_task.retry_after_lease_expiry(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, state, 2, 1, 3, NOW)

    assert event.to_state is AgentTaskState.READY
    assert event.task_version == 3
    assert event.attempt == 2


def test_retry_after_lease_expiry_requires_a_claimed_or_running_source_state() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.retry_after_lease_expiry(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.READY, 1, 1, 3, NOW)


def test_retry_after_lease_expiry_requires_attempts_remaining() -> None:
    """SPEC-ARO-029: exhausted retries are mark_stale()'s job, not this function's —
    resolved via AskUserQuestion in favor of the recovery-worker step's "retry 或 stale
    标记" over the separate "Retry Policy" section's FAILED_FINAL.
    """
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.retry_after_lease_expiry(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, 2, 3, 3, NOW)


@pytest.mark.parametrize("state", [AgentTaskState.CLAIMED, AgentTaskState.RUNNING])
def test_wait_for_tool_from_an_active_claim_produces_the_waiting_tool_state(state: AgentTaskState) -> None:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 4."""
    event = agent_task.wait_for_tool(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, state, 1, NOW)

    assert event.to_state is AgentTaskState.WAITING_TOOL
    assert event.task_version == 2


def test_wait_for_tool_requires_a_claimed_or_running_source_state() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.wait_for_tool(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.READY, 1, NOW)


@pytest.mark.parametrize("state", [AgentTaskState.CLAIMED, AgentTaskState.RUNNING])
def test_await_user_confirmation_from_an_active_claim_produces_the_awaiting_state(state: AgentTaskState) -> None:
    """SPEC-ARO-039/040 (phase-10 Conversational Intake): a process_user_message task
    whose reasoning produced a proposed_action outcome enters AWAITING_USER_CONFIRMATION
    instead of COMPLETED.
    """
    event = agent_task.await_user_confirmation(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, state, 1, NOW)

    assert event.to_state is AgentTaskState.AWAITING_USER_CONFIRMATION
    assert event.task_version == 2


def test_await_user_confirmation_requires_a_claimed_or_running_source_state() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.await_user_confirmation(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.READY, 1, NOW)


def test_dispatch_tool_from_confirmation_from_awaiting_confirmation_produces_waiting_tool() -> None:
    """SPEC-ARO-040: confirming a low/medium-risk proposed action dispatches the real
    tool request."""
    event = agent_task.dispatch_tool_from_confirmation(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.AWAITING_USER_CONFIRMATION, 2, NOW)

    assert event.to_state is AgentTaskState.WAITING_TOOL
    assert event.task_version == 3


@pytest.mark.parametrize("state", [AgentTaskState.CLAIMED, AgentTaskState.RUNNING, AgentTaskState.READY])
def test_dispatch_tool_from_confirmation_rejects_any_state_other_than_awaiting_confirmation(state: AgentTaskState) -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.dispatch_tool_from_confirmation(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, state, 2, NOW)


def test_await_approval_from_confirmation_from_awaiting_confirmation_produces_waiting_external() -> None:
    """SPEC-ARO-040: confirming a high/critical-risk proposed action creates a real
    governance approval request instead."""
    event = agent_task.await_approval_from_confirmation(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.AWAITING_USER_CONFIRMATION, 2, NOW)

    assert event.to_state is AgentTaskState.WAITING_EXTERNAL
    assert event.task_version == 3


def test_await_approval_from_confirmation_rejects_any_state_other_than_awaiting_confirmation() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.await_approval_from_confirmation(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, 2, NOW)


def test_decline_from_awaiting_confirmation_produces_completed_with_a_declined_result() -> None:
    event = agent_task.decline(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.AWAITING_USER_CONFIRMATION, 2, NOW)

    assert event.to_state is AgentTaskState.COMPLETED
    assert event.task_version == 3
    assert event.result_payload == '{"kind": "declined"}'


def test_decline_rejects_any_state_other_than_awaiting_confirmation() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.decline(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, 2, NOW)


def test_complete_from_tool_result_requires_waiting_tool() -> None:
    """SPEC-ARO-020: only the task a tool.completed.v1 delivery is actually for — WAITING_
    TOOL — can complete this way."""
    event = agent_task.complete_from_tool_result(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.WAITING_TOOL, 2, "ok", NOW)

    assert event.to_state is AgentTaskState.COMPLETED
    assert event.task_version == 3
    assert event.result_payload == "ok"


@pytest.mark.parametrize("state", [AgentTaskState.CLAIMED, AgentTaskState.RUNNING, AgentTaskState.COMPLETED])
def test_complete_from_tool_result_rejects_any_state_other_than_waiting_tool(state: AgentTaskState) -> None:
    """Deliberately narrower than complete()'s own CLAIMED/RUNNING guard — a worker must
    not be able to reach this entry point at all; only the tool-result consumer does.
    """
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.complete_from_tool_result(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, state, 2, "ok", NOW)


def test_complete_from_tool_result_rejects_a_blank_result_payload() -> None:
    with pytest.raises(ValueError):
        agent_task.complete_from_tool_result(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.WAITING_TOOL, 2, "   ", NOW)


def test_fail_from_tool_result_requires_waiting_tool() -> None:
    event = agent_task.fail_from_tool_result(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.WAITING_TOOL, 2, "tool crashed", NOW)

    assert event.to_state is AgentTaskState.FAILED_FINAL
    assert event.task_version == 3
    assert event.failure_reason == "tool crashed"


def test_fail_from_tool_result_rejects_any_state_other_than_waiting_tool() -> None:
    with pytest.raises(InvalidAgentTaskTransitionException):
        agent_task.fail_from_tool_result(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.CLAIMED, 2, "tool crashed", NOW)


def test_fail_from_tool_result_rejects_a_blank_failure_reason() -> None:
    with pytest.raises(ValueError):
        agent_task.fail_from_tool_result(AGENT_TASK_ID, WORKFLOW_INSTANCE_ID, AgentTaskState.WAITING_TOOL, 2, "   ", NOW)


@pytest.mark.parametrize("state", [AgentTaskState.COMPLETED, AgentTaskState.FAILED_FINAL, AgentTaskState.CANCELLED])
def test_terminal_states_report_is_terminal_true(state: AgentTaskState) -> None:
    assert state.is_terminal() is True


@pytest.mark.parametrize("state", [
    AgentTaskState.PENDING, AgentTaskState.READY, AgentTaskState.CLAIMED, AgentTaskState.RUNNING,
    AgentTaskState.WAITING_TOOL, AgentTaskState.WAITING_EXTERNAL, AgentTaskState.FAILED_RETRYABLE, AgentTaskState.STALE,
])
def test_non_terminal_states_report_is_terminal_false(state: AgentTaskState) -> None:
    """FAILED_RETRYABLE is deliberately non-terminal — it might still resolve to
    COMPLETED once SPEC-ARO-010 wires the retry path back to READY.
    """
    assert state.is_terminal() is False


@pytest.mark.parametrize("state", [AgentTaskState.READY, AgentTaskState.CLAIMED, AgentTaskState.RUNNING, AgentTaskState.STALE])
def test_claimable_states_report_is_claimable_true(state: AgentTaskState) -> None:
    assert state.is_claimable() is True


@pytest.mark.parametrize("state", [
    AgentTaskState.PENDING, AgentTaskState.WAITING_TOOL, AgentTaskState.WAITING_EXTERNAL, AgentTaskState.COMPLETED,
    AgentTaskState.FAILED_RETRYABLE, AgentTaskState.FAILED_FINAL, AgentTaskState.CANCELLED,
])
def test_non_claimable_states_report_is_claimable_false(state: AgentTaskState) -> None:
    """PENDING is deliberately excluded even though it's non-terminal: a task with unmet
    dependencies must be promoted to READY first (SPEC-ARO-007), never claimed directly.
    """
    assert state.is_claimable() is False
