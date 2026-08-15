"""Domain events raised by the aggregates in agentruntime.domain.model_*.

Frozen dataclasses only — no framework dependency. Base classes
(WorkflowDomainEvent / AgentTaskDomainEvent) are the Pythonic stand-in for the
Java sibling services' sealed-interface event contracts.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from agentruntime.domain.enums import AgentTaskState, CheckpointType, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    DefinitionVersion,
    IdempotencyKey,
    LeaseToken,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)


@dataclass(frozen=True, slots=True)
class WorkflowDomainEvent:
    """Common contract for every state transition raised by domain.workflow_instance."""

    workflow_instance_id: WorkflowInstanceId
    from_state: WorkflowState | None
    to_state: WorkflowState
    workflow_version: int
    """02-business-invariants §"Workflow Instance Invariants": "Every state transition increments workflowVersion."."""
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class WorkflowStarted(WorkflowDomainEvent):
    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    workflow_type: WorkflowType
    definition_id: WorkflowDefinitionId
    definition_version: DefinitionVersion


@dataclass(frozen=True, slots=True)
class WorkflowPaused(WorkflowDomainEvent):
    """02-business-invariants §"Pause / Resume Idempotency Invariants": "pauseGeneration
    increments on every successful pause."
    """

    pause_generation: int
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class WorkflowResumed(WorkflowDomainEvent):
    pause_generation: int
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class WorkflowCompleted(WorkflowDomainEvent):
    pass


@dataclass(frozen=True, slots=True)
class WorkflowFailed(WorkflowDomainEvent):
    """02-business-invariants: failure paths must retain an auditable reason."""

    failure_reason: str


@dataclass(frozen=True, slots=True)
class WorkflowCancelled(WorkflowDomainEvent):
    reason: str


@dataclass(frozen=True, slots=True)
class WorkflowWaitingForTool(WorkflowDomainEvent):
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 5: "Set
    workflow to WAITING_FOR_TOOL." No extra fields beyond the base — which Tool Request
    triggered the wait is recorded on the Tool Request/Checkpoint side, not duplicated
    here.
    """


@dataclass(frozen=True, slots=True)
class WorkflowWokenFromToolWait(WorkflowDomainEvent):
    """SPEC-ARO-020 04-use-cases UC-04 "消费 tool.completed", 03-state-machine §"外部事件
    唤醒": the counterpart to WorkflowWaitingForTool — a tool.completed.v1 delivery wakes
    WAITING_FOR_TOOL back to RUNNING. Deliberately not WorkflowResumed: that event means
    an explicit resume *command* (idempotencyKey, pauseGeneration semantics) waking a
    PAUSED workflow, a different trigger and a different source state entirely.
    """


@dataclass(frozen=True, slots=True)
class WorkflowWokenFromApprovalWait(WorkflowDomainEvent):
    """SPEC-ARO-021 04-use-cases UC-03 "消费 approval.granted", 03-state-machine §"外部事件
    唤醒": an approval.granted.v1 delivery with decision == "APPROVED" wakes
    WAITING_FOR_APPROVAL back to RUNNING. There is no WorkflowWaitingForApproval
    counterpart (unlike WorkflowWaitingForTool) — no spec has built the entry path into
    WAITING_FOR_APPROVAL yet (AgentTaskState's own WAITING_EXTERNAL docstring: "once an
    approval/verification/input wait exists"), so this event's own from_state is only
    ever reachable in practice once that future entry mechanism lands.
    """


@dataclass(frozen=True, slots=True)
class WorkflowWokenFromVerificationWait(WorkflowDomainEvent):
    """SPEC-ARO-022 04-use-cases UC-05 "消费 verification.completed", 03-state-machine
    §"外部事件唤醒": a verification.completed.v1 delivery wakes WAITING_FOR_VERIFICATION
    back to RUNNING — mirrors WorkflowWokenFromApprovalWait exactly; whether that then
    settles the workflow to COMPLETED is a task-graph-level question the caller answers
    afterward via CoordinateAgentTasksService, not something this event itself encodes.
    """


@dataclass(frozen=True, slots=True)
class AgentTaskDomainEvent:
    """Common contract for every state transition raised by domain.agent_task."""

    agent_task_id: AgentTaskId
    workflow_instance_id: WorkflowInstanceId
    to_state: AgentTaskState
    task_version: int
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class AgentTaskCreated(AgentTaskDomainEvent):
    """13-package-and-class-design: raised by Planner-produced task-graph nodes, never by
    direct tool calls. depends_on_task_keys mirrors TaskNode.depends_on and
    AgentTaskRecord.depends_on_task_keys: task-graph dependencies are matched by task_key
    (stable at graph-definition time), never by AgentTaskId (only assigned once a record
    is materialized, which siblings this task depends on may not be yet).
    """

    task_type: str
    depends_on_task_keys: frozenset[str]
    agent_role: str | None = None


@dataclass(frozen=True, slots=True)
class AgentTaskClaimed(AgentTaskDomainEvent):
    worker_id: str
    lease_token: LeaseToken
    lease_expires_at: datetime


@dataclass(frozen=True, slots=True)
class AgentTaskCompleted(AgentTaskDomainEvent):
    """02-business-invariants §"Agent Task Invariants": "Task completion event may be published once."."""

    result_payload: str


@dataclass(frozen=True, slots=True)
class AgentTaskFailed(AgentTaskDomainEvent):
    """02-business-invariants §"Agent Task Invariants": "Task completion must write ... an explicit failure reason."."""

    failure_reason: str


@dataclass(frozen=True, slots=True)
class AgentTaskStaled(AgentTaskDomainEvent):
    """SPEC-ARO-016 (Stale Generation Worker Result): raised when a worker's completion
    submission is rejected for carrying a stale pauseGeneration or workflowVersion —
    02-business-invariants' domain-rules "failure paths must retain auditable reasons"
    applies here too, hence `reason` rather than a bare state flip.
    """

    reason: str


@dataclass(frozen=True, slots=True)
class AgentTaskWaitingForTool(AgentTaskDomainEvent):
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 4: "Set
    task to WAITING_TOOL." No extra fields beyond the base — the triggering Tool Request
    is looked up by agent_task_id, not duplicated onto this event.
    """


@dataclass(frozen=True, slots=True)
class AgentTaskRetried(AgentTaskDomainEvent):
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5: "对 CLAIMED/RUNNING
    且 lease 过期的 task 做 retry 或 stale 标记" — the retry half (AgentTaskStaled/mark_stale()
    is the other, for the attempt-budget-exhausted case). `attempt` is the post-increment
    count, mirroring how AgentTaskClaimed/AgentTaskCompleted carry their own post-transition
    fields rather than requiring the reader to recompute them from task_version.
    """

    attempt: int


@dataclass(frozen=True, slots=True)
class CheckpointRecorded:
    """02-business-invariants §"Checkpoint Invariants": "Checkpoint payload must be parsed
    by schema version." workflow_version/cursor/checksum are SPEC-ARO-011's 01-domain-model/
    07-data-model minimal fields — see domain.checkpoint's module docstring for why cursor
    stays None here and checksum is computed in this layer, not infrastructure.
    """

    checkpoint_id: CheckpointId
    workflow_instance_id: WorkflowInstanceId
    type: CheckpointType
    schema_version: int
    payload: str
    occurred_at: datetime
    workflow_version: int
    cursor: str | None
    checksum: str


@dataclass(frozen=True, slots=True)
class ToolRequested:
    """02-business-invariants §"Tool Gateway Boundary": every ToolRequest carries the id of the
    Checkpoint that must have been persisted before it — the type system, not just a runtime
    check, makes a checkpoint-less tool request unrepresentable.
    """

    tool_request_id: ToolRequestId
    workflow_instance_id: WorkflowInstanceId
    agent_task_id: AgentTaskId
    preceding_checkpoint_id: CheckpointId
    tool_name: str
    request_payload: str
    occurred_at: datetime
    capability: str | None = None
    """SPEC-ARO-017 01-domain-model: one of Tool Request's own minimal fields, alongside
    toolName — the caller-declared capability this request should be authorized under.
    Still optional (skips authorization entirely when absent); when present,
    RequestToolService now checks it against CapabilityPolicyPort before this event is
    ever produced (SPEC-ARO-032 11-security §"Tool Gateway 强制路径").
    """
