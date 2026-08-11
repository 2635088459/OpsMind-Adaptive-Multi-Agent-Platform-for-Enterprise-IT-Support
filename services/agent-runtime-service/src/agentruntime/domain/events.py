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
class CheckpointRecorded:
    """02-business-invariants §"Checkpoint Invariants": "Checkpoint payload must be parsed by schema version."."""

    checkpoint_id: CheckpointId
    workflow_instance_id: WorkflowInstanceId
    type: CheckpointType
    schema_version: int
    payload: str
    occurred_at: datetime


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
