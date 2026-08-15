"""Application-layer persisted-projection records, passed across
agentruntime.application.ports_out. Plain dataclasses — no ORM/framework
dependency; SPEC-ARO-002 (schema baseline) maps these onto SQLAlchemy models.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
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
    # SPEC-ARO-028 01-domain-model §"Workflow Instance 是什么": "currentCheckpointId：最近
    # 一次稳定 checkpoint." Deliberately required (no default): every application service
    # that saves a WorkflowInstanceRecord must make an explicit choice about this field
    # (carry the existing pointer forward via dataclasses.replace()/current.
    # current_checkpoint_id, or advance it to a checkpoint just written in the same
    # transaction) rather than silently losing it to an unset default. Only updated when
    # a checkpoint write already coincides with this same save — never an extra save
    # purely to record it (07-data-model's own original deferral note on this column).
    current_checkpoint_id: CheckpointId | None
    # 07-data-model column: the instant this Workflow Instance reached any terminal state
    # (COMPLETED/FAILED/CANCELLED) — set once, by whichever of CompleteWorkflowService/
    # FailWorkflowService/CancelWorkflowService got there first.
    completed_at: datetime | None


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
    # SPEC-ARO-029 09-concurrency-and-idempotency/10-failure-handling: retry/attempt
    # counting for lease-expiry recovery. AgentTaskRow has carried both columns
    # (default=1/1) since 07-data-model; wiring them here as *defaulted* fields (unlike
    # SPEC-ARO-028's current_checkpoint_id/completed_at, deliberately made required) is
    # safe precisely because 1/1 matches the DB's own default and every pre-existing
    # construction site's prior (implicit) behavior — no silent data loss to guard
    # against by forcing an explicit choice everywhere.
    attempt: int = 1
    max_attempts: int = 1

    def is_lease_outstanding(self) -> bool:
        """Whether a worker currently holds a lease on this task (13-package-and-class-design:
        "Worker claim must use a lease").
        """
        return self.lease_token is not None and self.lease_expires_at is not None


@dataclass(frozen=True, slots=True)
class CheckpointRecord:
    """SPEC-ARO-011 01-domain-model/07-data-model: workflow_version/cursor/checksum are
    Checkpoint's own minimal fields, kw_only so a pre-SPEC-ARO-011 positional construction
    fails loudly instead of silently binding the wrong slot.
    """

    id: CheckpointId
    workflow_instance_id: WorkflowInstanceId
    type: CheckpointType
    schema_version: int
    payload: str
    recorded_at: datetime
    workflow_version: int = field(kw_only=True)
    checksum: str = field(kw_only=True)
    cursor: str | None = field(default=None, kw_only=True)


@dataclass(frozen=True, slots=True)
class ToolRequestRecord:
    """SPEC-ARO-017 01-domain-model/07-data-model: capability/gateway_correlation_id/
    policy_snapshot/result_payload/idempotency_key are Tool Request's own minimal fields
    — kw_only so a pre-SPEC-ARO-017 positional construction fails loudly instead of
    silently binding the wrong slot. gateway_correlation_id/policy_snapshot/result_payload
    stay None for every writer today: assigning a real gateway correlation id and taking
    a policy snapshot are dispatch-time concerns (SPEC-ARO-019), and result_payload is
    only ever populated by consuming tool.completed/tool.failed (SPEC-ARO-020) — this
    spec completes the aggregate's shape and its persistence round-trip, not those two
    specs' own logic.
    """

    id: ToolRequestId
    workflow_instance_id: WorkflowInstanceId
    agent_task_id: AgentTaskId
    preceding_checkpoint_id: CheckpointId
    tool_name: str
    request_payload: str
    status: ToolRequestStatus
    created_at: datetime
    updated_at: datetime
    capability: str | None = field(default=None, kw_only=True)
    gateway_correlation_id: str | None = field(default=None, kw_only=True)
    policy_snapshot: str | None = field(default=None, kw_only=True)
    result_payload: str | None = field(default=None, kw_only=True)
    idempotency_key: str | None = field(default=None, kw_only=True)


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


@dataclass(frozen=True, slots=True)
class PoisonEventRecord:
    """SPEC-ARO-024 10-failure-handling §"Poison Event": "事件无法反序列化、schema 缺字段或
    违反不变量时: 1. 写入 poison event 表或 dead letter. 2. 不推进 Workflow. 3. 发布
    observability alert. 4. 等待人工修复后 replay." Deliberately NOT recorded in
    processed_events: unlike a well-classified rejection (stale, not-found, ...), a
    poisoned delivery must remain replayable under the same event_id once whatever
    produced the malformed payload is fixed — marking it processed would permanently
    block that replay behind the dedup gate.
    """

    id: uuid.UUID
    event_id: str
    consumer_name: str
    event_type: str
    payload: str
    error_message: str
    occurred_at: datetime
    recorded_at: datetime
    # SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined" — None
    # until an operator flags this row as already triaged. Defaulted (not required, unlike
    # SPEC-ARO-028's current_checkpoint_id/completed_at): every existing construction site
    # already means "not yet quarantined," which None correctly and safely expresses.
    quarantined_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class AuditRecordEntry:
    """SPEC-ARO-034 12-observability §"Audit Events": "审计事件必须可长期保存: workflow
    transition, task transition, checkpoint created, tool request created, external
    event consumed, pause/resume, recovery decision, admin intervention." Mirrors the
    sibling ticket-workflow-service's own AuditRecordEntry (application/model/
    AuditRecordEntry.java) — same "one durable row per audited action" shape — with
    field names adapted to this domain's own vocabulary (workflow_instance_id/
    ticket_id/correlation_id/causation_id, already used everywhere else in this
    codebase) rather than that service's generic resourceType/resourceId/
    ticketStatusBefore/After pair, which describes a different aggregate shape.

    Unlike the Java sibling's AuditRecordPort.append() ("failures must propagate, not
    be swallowed — audit failure must roll back the caller's transaction"), this
    codebase has no cross-repository transaction to roll back into in the first place
    (every repository already opens its own short-lived session per call — see
    infrastructure.persistence.postgres.repositories' own module docstring) — the
    audit write here follows the same after-the-primary-write ordering
    OutboxRepository.append() already established, not a stricter guarantee this
    codebase's own persistence architecture doesn't otherwise provide anywhere.
    """

    id: uuid.UUID
    audit_type: str
    action: str
    resource_type: str
    resource_id: str
    workflow_instance_id: WorkflowInstanceId | None
    ticket_id: TicketId | None
    actor_type: str
    actor_id: str | None
    outcome: str
    correlation_id: str | None
    causation_id: str | None
    detail: str
    occurred_at: datetime
