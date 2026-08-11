"""13-package-and-class-design §"Class Boundaries": "Coordinator decides which task
graph nodes are runnable." Stateless domain functions — never persist anything or
call a port; the Application layer (CoordinateAgentTasksService) supplies live task
state and acts on the result.
"""

from __future__ import annotations

from agentruntime.domain.enums import AgentTaskState, JoinOutcome, JoinPolicy
from agentruntime.domain.task_graph import TaskGraph


def runnable_task_keys(graph: TaskGraph, state_by_task_key: dict[str, AgentTaskState]) -> frozenset[str]:
    """A node is runnable once it has no recorded state (never created) or is still PENDING,
    and its own join_policy is SATISFIED by its depends_on task keys' states.
    02-business-invariants §"Multi-Agent Orchestration Invariants": "Join policy must be
    explicit: all-success, first-success, quorum, or manual-review" — SPEC-ARO-010 replaced
    this function's original hardcoded "every dependency must be COMPLETED" rule (which
    made join_policy dead data — stored on every TaskNode, never once consulted) with a
    real evaluate_join() call per node, so FIRST_SUCCESS/QUORUM nodes can now proceed
    without waiting for every sibling to resolve. A node with zero dependencies is
    trivially runnable regardless of policy (evaluate_join([]) would otherwise return
    PENDING, which is wrong for a root node — there is nothing to join). A dependency that
    has no record at all yet (state_by_task_key.get(...) is None) means "not even
    attempted" — evaluate_join only ever sees dependencies that have started, so that case
    is handled here directly rather than inventing a placeholder AgentTaskState for it.

    Already-materialized nodes (any state other than PENDING — including READY, since
    materializing it once is enough) are excluded: this function answers "what needs a new
    record", not "what is currently claimable" (domain.agent_task.AgentTaskState.
    is_claimable() answers that).
    """
    runnable: set[str] = set()
    for node in graph.nodes:
        current_state = state_by_task_key.get(node.task_key)
        if current_state is not None and current_state is not AgentTaskState.PENDING:
            continue
        if not node.depends_on:
            runnable.add(node.task_key)
            continue
        dependency_states = [state_by_task_key.get(dependency_key) for dependency_key in node.depends_on]
        if any(state is None for state in dependency_states):
            continue
        if evaluate_join(node.join_policy, dependency_states) is JoinOutcome.SATISFIED:
            runnable.add(node.task_key)
    return frozenset(runnable)


def evaluate_join(policy: JoinPolicy, child_states: list[AgentTaskState]) -> JoinOutcome:
    """02-business-invariants §"Multi-Agent Orchestration Invariants": "Join policy must be
    explicit: all-success, first-success, quorum, or manual-review."

    FAILED_RETRYABLE deliberately does not count as "failed" here — a retryable failure
    might still resolve to COMPLETED, so it falls into the same "pending" bucket as an
    in-flight task; only FAILED_FINAL is a settled failure. Actually driving a
    FAILED_RETRYABLE child back to READY never happens today — domain.agent_task.fail()
    always produces FAILED_FINAL (SPEC-ARO-007) — attempt/maxAttempts-driven retry isn't
    named by any rule in 02-business-invariants or 08-transaction-and-outbox (SPEC-ARO-010's
    own two mapped LLD sections), so it stays out of scope here too; a correction of this
    docstring's earlier, self-authored "SPEC-ARO-010's job" note, which overstated what the
    LLD actually asks for.
    """
    if not child_states:
        return JoinOutcome.PENDING

    completed = sum(1 for s in child_states if s is AgentTaskState.COMPLETED)
    failed = sum(1 for s in child_states if s in (AgentTaskState.FAILED_FINAL, AgentTaskState.CANCELLED))
    pending = len(child_states) - completed - failed

    if policy is JoinPolicy.ALL_SUCCESS:
        if failed > 0:
            return JoinOutcome.FAILED
        return JoinOutcome.SATISFIED if pending == 0 else JoinOutcome.PENDING

    if policy is JoinPolicy.FIRST_SUCCESS:
        if completed > 0:
            return JoinOutcome.SATISFIED
        return JoinOutcome.FAILED if pending == 0 else JoinOutcome.PENDING

    if policy is JoinPolicy.QUORUM:
        quorum = (len(child_states) // 2) + 1
        if completed >= quorum:
            return JoinOutcome.SATISFIED
        return JoinOutcome.FAILED if pending == 0 else JoinOutcome.PENDING

    # JoinPolicy.MANUAL_REVIEW
    return JoinOutcome.NEEDS_MANUAL_REVIEW if pending == 0 else JoinOutcome.PENDING


def is_workflow_settled(graph: TaskGraph, state_by_task_key: dict[str, AgentTaskState]) -> bool:
    """SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6: "If
    workflow reaches terminal state, update workflow and insert workflow.completed.v1."
    Settled means there is nothing left this Workflow Instance could still do: nothing
    currently runnable, and every node that was ever materialized has reached a terminal
    AgentTaskState. A node that never got materialized at all because
    runnable_task_keys()'s own evaluate_join() call permanently returned FAILED for it
    (an unsatisfiable join — e.g. every ALL_SUCCESS dependency ended in FAILED_FINAL) is
    not double-counted here: it stays invisible to this check by design, exactly the way
    it stays invisible to runnable_task_keys() — a graph can be fully settled with some of
    its nodes never having existed as a record at all.
    """
    if runnable_task_keys(graph, state_by_task_key):
        return False
    return all(
        state_by_task_key[node.task_key].is_terminal()
        for node in graph.nodes
        if node.task_key in state_by_task_key
    )


def workflow_outcome_is_success(graph: TaskGraph, state_by_task_key: dict[str, AgentTaskState]) -> bool:
    """Only meaningful once is_workflow_settled() is True for the same graph/state.
    Success requires every single node in the graph — not just its "leaf" nodes — to have
    reached AgentTaskState.COMPLETED: a node that was materialized but ended in
    FAILED_FINAL/CANCELLED is a failure, and so is a node that was never materialized at
    all (its own join permanently failed, per is_workflow_settled()'s docstring) — the
    automation as a whole did not do everything the task graph described.
    """
    return all(state_by_task_key.get(node.task_key) is AgentTaskState.COMPLETED for node in graph.nodes)
