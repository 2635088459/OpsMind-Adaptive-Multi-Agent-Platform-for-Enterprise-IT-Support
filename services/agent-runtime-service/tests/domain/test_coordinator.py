from __future__ import annotations

import pytest

from agentruntime.domain import coordinator
from agentruntime.domain.enums import AgentTaskState, JoinOutcome, JoinPolicy
from agentruntime.domain.task_graph import TaskGraph, TaskNode

pytestmark = pytest.mark.unit


def test_root_node_with_no_dependencies_is_runnable_when_never_started() -> None:
    graph = TaskGraph((TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),))

    runnable = coordinator.runnable_task_keys(graph, {})

    assert runnable == frozenset({"collect"})


def test_downstream_node_is_not_runnable_until_its_dependency_completes() -> None:
    graph = TaskGraph((
        TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("remediate", "apply_fix", frozenset({"collect"}), JoinPolicy.ALL_SUCCESS),
    ))

    runnable_before = coordinator.runnable_task_keys(graph, {"collect": AgentTaskState.RUNNING})
    assert runnable_before == frozenset()

    runnable_after = coordinator.runnable_task_keys(graph, {"collect": AgentTaskState.COMPLETED})
    assert runnable_after == frozenset({"remediate"})


def test_all_success_join_is_satisfied_only_when_every_child_completes() -> None:
    assert coordinator.evaluate_join(JoinPolicy.ALL_SUCCESS, [AgentTaskState.COMPLETED, AgentTaskState.RUNNING]) is JoinOutcome.PENDING
    assert coordinator.evaluate_join(JoinPolicy.ALL_SUCCESS, [AgentTaskState.COMPLETED, AgentTaskState.COMPLETED]) is JoinOutcome.SATISFIED
    assert coordinator.evaluate_join(JoinPolicy.ALL_SUCCESS, [AgentTaskState.COMPLETED, AgentTaskState.FAILED_FINAL]) is JoinOutcome.FAILED


def test_first_success_join_is_satisfied_as_soon_as_one_child_completes() -> None:
    assert coordinator.evaluate_join(JoinPolicy.FIRST_SUCCESS, [AgentTaskState.COMPLETED, AgentTaskState.RUNNING]) is JoinOutcome.SATISFIED
    assert coordinator.evaluate_join(JoinPolicy.FIRST_SUCCESS, [AgentTaskState.FAILED_FINAL, AgentTaskState.FAILED_FINAL]) is JoinOutcome.FAILED


def test_manual_review_join_defers_once_every_child_is_terminal() -> None:
    assert coordinator.evaluate_join(JoinPolicy.MANUAL_REVIEW, [AgentTaskState.COMPLETED, AgentTaskState.FAILED_FINAL]) is JoinOutcome.NEEDS_MANUAL_REVIEW


def test_a_retryable_failure_does_not_settle_the_join_the_way_a_final_failure_does() -> None:
    """SPEC-ARO-007: FAILED_RETRYABLE might still resolve to COMPLETED, so it must not be
    treated the same as FAILED_FINAL — the join stays PENDING, not FAILED, while it's
    outstanding.
    """
    assert coordinator.evaluate_join(JoinPolicy.ALL_SUCCESS, [AgentTaskState.COMPLETED, AgentTaskState.FAILED_RETRYABLE]) is JoinOutcome.PENDING


def test_quorum_join_is_satisfied_once_a_majority_of_children_complete() -> None:
    """Quorum of 3 is 2 — (3 // 2) + 1."""
    assert coordinator.evaluate_join(
        JoinPolicy.QUORUM, [AgentTaskState.COMPLETED, AgentTaskState.RUNNING, AgentTaskState.RUNNING]
    ) is JoinOutcome.PENDING
    assert coordinator.evaluate_join(
        JoinPolicy.QUORUM, [AgentTaskState.COMPLETED, AgentTaskState.COMPLETED, AgentTaskState.RUNNING]
    ) is JoinOutcome.SATISFIED
    assert coordinator.evaluate_join(
        JoinPolicy.QUORUM, [AgentTaskState.COMPLETED, AgentTaskState.FAILED_FINAL, AgentTaskState.FAILED_FINAL]
    ) is JoinOutcome.FAILED


def test_first_success_downstream_node_is_runnable_before_every_sibling_resolves() -> None:
    """SPEC-ARO-010: FIRST_SUCCESS must not wait for every dependency — one COMPLETED sibling
    is enough, even while another is still RUNNING.
    """
    graph = TaskGraph((
        TaskNode("probe_a", "probe", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("probe_b", "probe", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("remediate", "apply_fix", frozenset({"probe_a", "probe_b"}), JoinPolicy.FIRST_SUCCESS),
    ))

    runnable = coordinator.runnable_task_keys(
        graph, {"probe_a": AgentTaskState.COMPLETED, "probe_b": AgentTaskState.RUNNING}
    )

    assert runnable == frozenset({"remediate"})


def test_quorum_downstream_node_is_not_runnable_until_the_majority_resolves() -> None:
    graph = TaskGraph((
        TaskNode("vote_a", "vote", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("vote_b", "vote", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("vote_c", "vote", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("decide", "apply_decision", frozenset({"vote_a", "vote_b", "vote_c"}), JoinPolicy.QUORUM),
    ))
    state_by_task_key = {
        "vote_a": AgentTaskState.COMPLETED, "vote_b": AgentTaskState.RUNNING, "vote_c": AgentTaskState.RUNNING,
    }

    assert coordinator.runnable_task_keys(graph, state_by_task_key) == frozenset()

    state_by_task_key["vote_b"] = AgentTaskState.COMPLETED
    assert coordinator.runnable_task_keys(graph, state_by_task_key) == frozenset({"decide"})


def test_a_single_node_graph_is_settled_and_successful_once_it_completes() -> None:
    graph = TaskGraph((TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),))

    assert coordinator.is_workflow_settled(graph, {"collect": AgentTaskState.RUNNING}) is False
    assert coordinator.is_workflow_settled(graph, {"collect": AgentTaskState.COMPLETED}) is True
    assert coordinator.workflow_outcome_is_success(graph, {"collect": AgentTaskState.COMPLETED}) is True


def test_a_linear_chain_is_not_settled_while_its_downstream_node_is_still_unmaterialized() -> None:
    graph = TaskGraph((
        TaskNode("collect", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("remediate", "apply_fix", frozenset({"collect"}), JoinPolicy.ALL_SUCCESS),
    ))

    # "remediate" has no record at all yet, but is newly runnable once "collect" completes —
    # the graph is not settled just because every *existing* record is terminal.
    assert coordinator.is_workflow_settled(graph, {"collect": AgentTaskState.COMPLETED}) is False

    assert coordinator.is_workflow_settled(
        graph, {"collect": AgentTaskState.COMPLETED, "remediate": AgentTaskState.COMPLETED}
    ) is True


def _diamond_graph() -> TaskGraph:
    return TaskGraph((
        TaskNode("start", "collect_diagnostics", frozenset(), JoinPolicy.ALL_SUCCESS),
        TaskNode("branch_a", "probe", frozenset({"start"}), JoinPolicy.ALL_SUCCESS),
        TaskNode("branch_b", "probe", frozenset({"start"}), JoinPolicy.ALL_SUCCESS),
        TaskNode("join", "apply_fix", frozenset({"branch_a", "branch_b"}), JoinPolicy.ALL_SUCCESS),
    ))


def test_a_diamond_graph_is_settled_and_successful_once_every_branch_and_the_join_complete() -> None:
    graph = _diamond_graph()
    state_by_task_key = {
        "start": AgentTaskState.COMPLETED, "branch_a": AgentTaskState.COMPLETED,
        "branch_b": AgentTaskState.COMPLETED, "join": AgentTaskState.COMPLETED,
    }

    assert coordinator.is_workflow_settled(graph, state_by_task_key) is True
    assert coordinator.workflow_outcome_is_success(graph, state_by_task_key) is True


def test_a_diamond_graph_settles_as_a_failure_when_one_branch_permanently_fails_the_join() -> None:
    """"join"'s ALL_SUCCESS dependency on "branch_b" can never be satisfied once "branch_b"
    is FAILED_FINAL, so "join" is never materialized — the graph is still settled (nothing
    is runnable and every materialized node is terminal), just not successfully.
    """
    graph = _diamond_graph()
    state_by_task_key = {
        "start": AgentTaskState.COMPLETED, "branch_a": AgentTaskState.COMPLETED,
        "branch_b": AgentTaskState.FAILED_FINAL,
    }

    assert coordinator.is_workflow_settled(graph, state_by_task_key) is True
    assert coordinator.workflow_outcome_is_success(graph, state_by_task_key) is False
