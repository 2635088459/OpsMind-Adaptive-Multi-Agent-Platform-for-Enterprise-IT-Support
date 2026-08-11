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
