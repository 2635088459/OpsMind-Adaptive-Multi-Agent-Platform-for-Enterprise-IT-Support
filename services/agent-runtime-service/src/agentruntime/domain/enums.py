"""Domain enums. Pure stdlib — no framework dependency (SPEC-ARO-001 domain-rules)."""

from __future__ import annotations

from enum import Enum, auto


class WorkflowState(Enum):
    """SPEC-ARO-001 domain-rules: Agent Workflow state belongs only to Agent Runtime
    (02-business-invariants §"State Ownership"). Never mirrors Ticket Workflow's own status.
    Full member set mirrors 03-state-machine §"Workflow State" exactly (SPEC-ARO-004
    Workflow Instance Aggregate). Not every member is reachable yet: the WAITING_FOR_*
    wake-ups are written by the external-event consumers owned by SPEC-ARO-020/021/022,
    and this codebase compresses the transient STARTING/PAUSING/RESUMING steps into their
    surrounding atomic transition (domain.workflow_instance.start()/pause()/resume() go
    straight CREATED->RUNNING / RUNNING->PAUSED / PAUSED->RUNNING) rather than persisting
    a separate row write for the transient step — nothing today needs to observe that
    window, so this is the same "atomic step, not a literal two-phase state machine"
    compression already used for pause/resume in SPEC-ARO-003. The members still exist
    here so is_terminal()/pausability/persistence never need to change shape again once
    those later specs start writing them.
    """

    CREATED = auto()
    STARTING = auto()
    RUNNING = auto()
    WAITING_FOR_APPROVAL = auto()
    WAITING_FOR_TOOL = auto()
    WAITING_FOR_VERIFICATION = auto()
    WAITING_FOR_INPUT = auto()
    PAUSING = auto()
    PAUSED = auto()
    RESUMING = auto()
    COMPLETED = auto()
    FAILED = auto()
    CANCELLED = auto()

    def is_terminal(self) -> bool:
        """02-business-invariants §"Workflow Instance Invariants": no new Agent Task past a terminal state."""
        return self in _TERMINAL_WORKFLOW_STATES

    def is_waiting(self) -> bool:
        """03-state-machine §"External Event Wake-Up": the four states an external domain
        event (approval/tool/verification/input) can wake back to RUNNING.
        """
        return self in _WAITING_WORKFLOW_STATES


_TERMINAL_WORKFLOW_STATES = frozenset({WorkflowState.COMPLETED, WorkflowState.FAILED, WorkflowState.CANCELLED})
_WAITING_WORKFLOW_STATES = frozenset({
    WorkflowState.WAITING_FOR_APPROVAL, WorkflowState.WAITING_FOR_TOOL,
    WorkflowState.WAITING_FOR_VERIFICATION, WorkflowState.WAITING_FOR_INPUT,
})


class AgentTaskState(Enum):
    """02-business-invariants §"Agent Task Invariants". Full member set mirrors
    03-state-machine §"Agent Task State" exactly (SPEC-ARO-007 Agent Task Aggregate).
    Not every member is reachable yet: WAITING_TOOL is written once a spec actually
    transitions the task when a Tool Request is dispatched (SPEC-ARO-019), WAITING_EXTERNAL
    once an approval/verification/input wait exists (mirrors WorkflowState's own
    WAITING_FOR_* deferral), and STALE once claim-generation invalidation is wired
    (SPEC-ARO-016, Stale Generation Worker Result) — this enum exists so those specs never
    need to change its shape again.
    """

    PENDING = auto()
    """Materialized but blocked on an unmet dependency. domain.coordinator.
    materialize_runnable_tasks today only ever materializes already-satisfied nodes
    (straight to READY), so no current caller produces a PENDING record — this member is
    reserved for a future eager-materialization strategy (phase-02's own Planner Task
    Graph spec, SPEC-ARO-008) that creates the whole graph upfront and promotes nodes to
    READY as their dependencies resolve.
    """

    READY = auto()
    """Dependencies satisfied; claimable. What domain.agent_task.create() produces today
    for every task CoordinateAgentTasksService materializes, since that's only ever
    called for already-runnable graph nodes.
    """

    CLAIMED = auto()
    RUNNING = auto()
    WAITING_TOOL = auto()
    WAITING_EXTERNAL = auto()
    """First given a real writer by SPEC-ARO-040 (phase-10 Conversational Intake): the
    confirm-with-a-high-risk-action branch (agent_task.await_approval_from_confirmation())
    enters this state while a real governance approval request is pending.
    """

    AWAITING_USER_CONFIRMATION = auto()
    """SPEC-ARO-039/040 (phase-10 Conversational Intake): a `process_user_message` task
    whose ConversationReasoningPort decided on a "proposed_action" outcome enters this
    state instead of COMPLETED — pausing until the employee explicitly confirms or
    declines it (SPEC-ARO-040's own confirm/decline endpoints). Distinct from
    WAITING_EXTERNAL (that member is reserved for an approval/verification/input wait
    triggered by an *external domain event*, per its own docstring) — this wait is
    ended by a direct employee action against this same conversation, not an event
    consumed from another service.
    """

    COMPLETED = auto()

    FAILED_RETRYABLE = auto()
    """Not terminal — 02-business-invariants (SPEC-ARO-010, Task Completion and Join
    Policy) owns deciding when a failure is retryable (attempt vs maxAttempts) and how it
    returns to READY; until that exists, domain.agent_task.fail() always produces
    FAILED_FINAL, so nothing currently sets this.
    """

    FAILED_FINAL = auto()
    CANCELLED = auto()

    STALE = auto()
    """Claim generation expired or workflow version mismatched (09-concurrency-and-
    idempotency already rejects a stale completion via StalePauseGenerationException at
    the application layer) — persisting that outcome as this state, and the path back to
    claimable, is SPEC-ARO-016's job.
    """

    def is_terminal(self) -> bool:
        return self in _TERMINAL_TASK_STATES

    def is_claimable(self) -> bool:
        """READY is claimable outright; CLAIMED/RUNNING/STALE are only reclaimable once
        their existing lease has expired (checked by the caller) — PENDING is
        deliberately excluded: a task whose dependencies are not yet satisfied must be
        promoted to READY first, never claimed straight from PENDING even if a caller's
        dependency check happens to also pass.
        """
        return self in _CLAIMABLE_TASK_STATES


_TERMINAL_TASK_STATES = frozenset({AgentTaskState.COMPLETED, AgentTaskState.FAILED_FINAL, AgentTaskState.CANCELLED})
_CLAIMABLE_TASK_STATES = frozenset({AgentTaskState.READY, AgentTaskState.CLAIMED, AgentTaskState.RUNNING, AgentTaskState.STALE})


class CheckpointType(Enum):
    """02-business-invariants §"Checkpoint Invariants": "a checkpoint must exist before any
    external side effect" and "every recoverable waiting state must include a checkpoint".
    """

    STARTED = auto()
    """SPEC-ARO-005 04-use-cases UC-01: recorded once a Workflow Instance is created and
    before the Planner materializes its initial task graph.
    """

    AFTER_TASK = auto()
    """SPEC-ARO-008 04-use-cases UC-02 step 5: recorded once an Agent Task reaches
    COMPLETED or FAILED_FINAL, before the Coordinator re-scans the task graph for newly-
    runnable downstream tasks.
    """

    PRE_TOOL_CALL = auto()
    """Recorded immediately before a ToolRequest is dispatched through the Tool Gateway."""

    PRE_KNOWLEDGE_RETRIEVAL = auto()
    """SPEC-ARO-039 (phase-10 Conversational Intake): recorded immediately before
    querying 04-memory-knowledge — the same "checkpoint before any external side
    effect" invariant PRE_TOOL_CALL already enforces, but knowledge retrieval is not a
    ToolRequest (02-business-invariants §"Tool Gateway Boundary" scopes that boundary to
    Agent SDK tool calls specifically), so it gets its own, distinctly-named type
    rather than overloading PRE_TOOL_CALL's own meaning.
    """

    PRE_APPROVAL_REQUEST = auto()
    """SPEC-ARO-040 (phase-10 Conversational Intake): recorded immediately before
    calling 06-policy-approval-governance's real request-approval endpoint — mirrors
    PRE_KNOWLEDGE_RETRIEVAL's own reasoning (a real external call that is neither a
    ToolRequest nor a knowledge-retrieval query gets its own distinctly-named type).
    """

    WAIT_STATE = auto()
    """Recorded when a Workflow Instance enters a recoverable waiting state."""

    PAUSE_POINT = auto()
    """Recorded when a Workflow Instance is paused."""

    RECOVERY_SNAPSHOT = auto()
    """Recorded as an explicit recovery snapshot ahead of a crash-recovery-sensitive step."""


class JoinPolicy(Enum):
    """02-business-invariants §"Multi-Agent Orchestration Invariants": "Join policy must be explicit"."""

    ALL_SUCCESS = auto()
    FIRST_SUCCESS = auto()
    QUORUM = auto()
    MANUAL_REVIEW = auto()


class JoinOutcome(Enum):
    """Result of evaluating a JoinPolicy against a task's sibling states."""

    PENDING = auto()
    """Not enough sibling tasks have reached a terminal state yet."""

    SATISFIED = auto()
    """The join policy's success condition is met."""

    FAILED = auto()
    """The join policy's success condition can no longer be met."""

    NEEDS_MANUAL_REVIEW = auto()
    """JoinPolicy.MANUAL_REVIEW, or an ambiguous outcome the policy defers to a human."""


class ToolRequestStatus(Enum):
    """02-business-invariants §"Tool Gateway Boundary": "Tool results must return through
    tool.completed or tool.failed".
    """

    PENDING = auto()
    DISPATCHED = auto()
    COMPLETED = auto()
    FAILED = auto()


class OutboxStatus(Enum):
    """08-transaction-and-outbox §"Outbox Publisher": "Mark published_at after success.
    Move to DEAD_LETTER after repeated failures."
    """

    PENDING = auto()
    """Not yet published; a failed attempt also stays PENDING (available_at pushed back for backoff)."""

    PUBLISHED = auto()
    DEAD_LETTER = auto()
    """attempts exceeded the configured maximum; requires manual/ops intervention."""
