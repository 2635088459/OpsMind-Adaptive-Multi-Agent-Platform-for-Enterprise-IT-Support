"""Application-layer persisted-projection records, passed across
agentruntime.application.ports_out. Plain dataclasses — no ORM/framework
dependency; SPEC-ARO-002 (schema baseline) maps these onto SQLAlchemy models.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from agentruntime.domain.enums import AgentTaskState, CheckpointType, OutboxStatus, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CausationId,
    CheckpointId,
    CorrelationId,
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
class WorkflowInstanceRecord:
    id: WorkflowInstanceId
    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    workflow_type: WorkflowType
    definition_id: WorkflowDefinitionId
    definition_version: DefinitionVersion
    state: WorkflowState
    workflow_version: int
    pause_generation: int
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class AgentTaskRecord:
    """task_key correlates this instance to its TaskNode in the owning TaskGraph;
    depends_on_task_keys mirrors the node's graph-level dependencies so
    ClaimAgentTaskService can resolve completion without reloading the whole graph.
    """

    id: AgentTaskId
    workflow_instance_id: WorkflowInstanceId
    task_key: str
    task_type: str
    depends_on_task_keys: frozenset[str]
    state: AgentTaskState
    task_version: int
    worker_id: str | None
    lease_token: LeaseToken | None
    lease_expires_at: datetime | None
    result_payload: str | None
    failure_reason: str | None
    # 09-concurrency-and-idempotency §"Task Claim": "pauseGeneration must be copied into
    # the task claim" — captured from the owning Workflow Instance's pause_generation at
    # claim time (0 before the task is ever claimed); §"Workflow Version": "For pause/
    # resume, it must also validate pauseGeneration" — CompleteAgentTaskService compares
    # this stored value against the Workflow Instance's *current* pause_generation.
    pause_generation: int
    created_at: datetime
    updated_at: datetime
    # SPEC-ARO-009 05-api-contracts "Claim Task" / 01-domain-model: the role a worker must
    # present to claim this task through ClaimAgentTaskService.claim_ready() (the
    # role-based batch endpoint) — None means only the exact-task-key claim path can
    # reach it. Defaulted, not required: no Planner capability assigns it yet (mirrors
    # the SPEC-ARO-007 deferral of attempt/maxAttempts/inputPayload).
    agent_role: str | None = None

    def is_lease_outstanding(self) -> bool:
        """Whether a worker currently holds a lease on this task (13-package-and-class-design:
        "Worker claim must use a lease").
        """
        return self.lease_token is not None and self.lease_expires_at is not None


@dataclass(frozen=True, slots=True)
class CheckpointRecord:
    id: CheckpointId
    workflow_instance_id: WorkflowInstanceId
    type: CheckpointType
    schema_version: int
    payload: str
    recorded_at: datetime


@dataclass(frozen=True, slots=True)
class ToolRequestRecord:
    id: ToolRequestId
    workflow_instance_id: WorkflowInstanceId
    agent_task_id: AgentTaskId
    preceding_checkpoint_id: CheckpointId
    tool_name: str
    request_payload: str
    status: ToolRequestStatus
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class OutboxRecord:
    """02-business-invariants §"Event Handling Invariants": "Every published event must go
    through outbox" and "must include workflowInstanceId, ticketId, correlationId, and
    causationId."
    """

    outbox_id: uuid.UUID
    workflow_instance_id: WorkflowInstanceId
    ticket_id: TicketId
    correlation_id: CorrelationId
    causation_id: CausationId
    event_type: str
    schema_version: int
    payload: str
    occurred_at: datetime
    # 08-transaction-and-outbox §"Outbox Publisher". Callers that only append
    # (StartWorkflowService and friends) never set these — they default to a
    # freshly-written, unpublished, immediately-dispatchable row;
    # DispatchOutboxEventsService is the only code that reads/advances them.
    status: OutboxStatus = OutboxStatus.PENDING
    attempts: int = 0
    available_at: datetime | None = None
    """Defaults to occurred_at (available immediately) — see __post_init__. Pushed
    forward by DispatchOutboxEventsService on a failed publish attempt (backoff)."""
    published_at: datetime | None = None

    def __post_init__(self) -> None:
        if not self.event_type or not self.event_type.strip():
            raise ValueError("event_type must not be blank")
        if self.available_at is None:
            object.__setattr__(self, "available_at", self.occurred_at)


@dataclass(frozen=True, slots=True)
class ToolDispatchAcknowledgement:
    tool_request_id: ToolRequestId
    status: ToolRequestStatus
    acknowledged_at: datetime


@dataclass(frozen=True, slots=True)
class TicketSnapshot:
    """02-business-invariants §"State Ownership": "Runtime may read Ticket snapshots but must
    not directly write Ticket lifecycle state." Read-only projection; there is no write-back
    path from Runtime to Ticket Workflow through this type.
    """

    ticket_id: TicketId
    ticket_status: str
    ticket_version: int
    observed_at: datetime


@dataclass(frozen=True, slots=True)
class CommandIdempotencyRecord:
    """07-data-model §"command_idempotency" / 09-concurrency-and-idempotency
    §"Command Idempotency": "Start, Pause, Resume, Complete Task, and Request Tool must
    include idempotencyKey ... Same key with different request hash must return
    conflict." See agentruntime.application.services.idempotency.CommandIdempotencyGuard,
    the single place all five commands go through this table.
    """

    idempotency_key: IdempotencyKey
    command_type: str
    target_id: str | None
    request_hash: str | None
    response_json: str | None
    created_at: datetime
    expires_at: datetime | None
