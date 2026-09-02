"""SPEC-ARO-002 / 07-data-model: SQLAlchemy ORM models for the `agent_runtime`
PostgreSQL schema. This module is the entire ORM boundary — application and
domain code never see these classes (enforced by import-linter's "Application
must not depend on infrastructure" contract); infrastructure.persistence.
postgres.repositories translates between these rows and the application-layer
dataclass records.

Several columns exist here because 07-data-model lists them, but SPEC-ARO-001's
application services don't populate them yet — those stay NULL/defaulted until
the spec that owns the behavior lands (noted per-column below). Adding the
column now avoids a second migration later for a value every future spec
already expects to find a home for.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Index, Integer, MetaData, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

SCHEMA = "agent_runtime"


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA)


class WorkflowInstanceRow(Base):
    __tablename__ = "workflow_instances"
    __table_args__ = (
        # 02-business-invariants §"Workflow Instance Invariants": "At most one
        # active Workflow Instance may exist for the same ticketId +
        # ticketCycleId + workflowType." A plain UNIQUE constraint can't
        # express "only while non-terminal", so this is created by the
        # migration itself as a partial unique index instead of listed here.
        # SPEC-ARO-006 05-api-contracts "GET /workflows/by-ticket/{ticketId}".
        Index("ix_workflow_instances_ticket_id", "ticket_id"),
        # SPEC-ARO-042 api-contract: backs find_most_recent_by_requester_and_workflow_type.
        Index("ix_workflow_instances_requester_subject", "requester_subject"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    ticket_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    ticket_cycle_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    workflow_type: Mapped[str] = mapped_column(String(100), nullable=False)
    definition_id: Mapped[str] = mapped_column(String(200), nullable=False)
    definition_version: Mapped[int] = mapped_column(Integer, nullable=False)
    state: Mapped[str] = mapped_column(String(40), nullable=False)
    workflow_version: Mapped[int] = mapped_column(BigInteger, nullable=False)
    pause_generation: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # 07-data-model column; deliberately still unpopulated after SPEC-ARO-011 (which
    # wired up Checkpoint's own workflow_version/checksum fields): every write to this
    # table is a strict optimistic-version CAS (workflow_version must be exactly
    # existing+1), so updating this pointer from a task-level checkpoint write
    # (PRE_TOOL_CALL/AFTER_TASK) would force a save purely to record it — a real,
    # unrequested side effect that would spuriously invalidate any concurrent command
    # still holding the pre-bump workflow_version (StaleWorkflowVersionException).
    # SPEC-ARO-012 (checkpoints around pause/resume/external waits) is the natural owner:
    # those checkpoints coincide with a state transition that is already bumping
    # workflow_version for its own reason, so setting this pointer there is free.
    current_checkpoint_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # 07-data-model column; no application service reaches a terminal
    # workflow state yet (SPEC-ARO-001 never wired a CompleteWorkflowService).
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # SPEC-ARO-042 (phase-10 Conversational Intake): the requester subject (JWT `sub`)
    # that owns this instance — only StartConversationService (SPEC-ARO-038) populates
    # it; every pre-existing workflow_type leaves it NULL, matching those instances'
    # lack of a single directly-identified human owner.
    requester_subject: Mapped[str | None] = mapped_column(String(200), nullable=True)
    # SPEC-ARO-041 (phase-10 Conversational Intake): the owning ticket's own
    # optimistic-concurrency version, as last observed by this service — see
    # WorkflowInstanceRecord.ticket_version's own docstring for the full rule.
    ticket_version: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # SPEC-ARO-041: the owning ticket's real displayId, captured at conversation
    # creation — see WorkflowInstanceRecord.ticket_display_id's own docstring.
    ticket_display_id: Mapped[str | None] = mapped_column(String(50), nullable=True)


class AgentTaskRow(Base):
    __tablename__ = "agent_tasks"
    __table_args__ = (
        UniqueConstraint("workflow_instance_id", "task_key", name="uq_agent_tasks_workflow_instance_task_key"),
        Index("ix_agent_tasks_workflow_instance_state", "workflow_instance_id", "state"),
        Index("ix_agent_tasks_role_state_claim_expiry", "agent_role", "state", "claim_expires_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    workflow_instance_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.workflow_instances.id"), nullable=False
    )
    task_key: Mapped[str] = mapped_column(String(200), nullable=False)
    # 07-data-model column; Planner/TaskNode don't carry an agent role yet
    # (phase-02 agent-task-orchestration).
    agent_role: Mapped[str | None] = mapped_column(String(100), nullable=True)
    task_type: Mapped[str] = mapped_column(String(200), nullable=False)
    state: Mapped[str] = mapped_column(String(40), nullable=False)
    depends_on_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    input_payload_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    result_payload_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    # 07-data-model columns; retry/attempt counting is phase-02/phase-08.
    attempt: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    max_attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    claim_owner: Mapped[str | None] = mapped_column(String(200), nullable=True)
    claim_token: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    claim_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # 09-concurrency-and-idempotency §"Task Claim": "pauseGeneration must be copied
    # into the task claim" — the owning Workflow Instance's pause_generation at claim
    # time, compared against its live value on completion (StalePauseGenerationException).
    pause_generation: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    task_version: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CheckpointRow(Base):
    __tablename__ = "checkpoints"
    __table_args__ = (
        Index("ix_checkpoints_workflow_instance_created_desc", "workflow_instance_id", "created_at"),
        Index("ix_checkpoints_workflow_instance_version", "workflow_instance_id", "workflow_version"),
        # SPEC-ARO-008 04-use-cases UC-02 step 6: CoordinateAgentTasksService.
        # unlock_downstream_tasks() looks up the STARTED checkpoint by type on every
        # task completion — a hot path that must not table-scan.
        Index("ix_checkpoints_workflow_instance_type", "workflow_instance_id", "checkpoint_type"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    workflow_instance_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.workflow_instances.id"), nullable=False
    )
    # SPEC-ARO-011 01-domain-model: one of Checkpoint's own minimal fields — the owning
    # Workflow Instance's workflow_version at the moment this checkpoint was recorded.
    # Still declared nullable (unlike CheckpointRecord.workflow_version, which is
    # required) only because the column predates this spec and a hard NOT NULL
    # migration on a possibly-populated production table is a separate, deliberate
    # step, not implied by wiring the write path.
    workflow_version: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    checkpoint_type: Mapped[str] = mapped_column(String(40), nullable=False)
    # 07-data-model column; recoverable-stream cursor, not yet produced by any
    # consumer (phase-06 external-event-consumption) — domain.checkpoint.record()
    # already accepts and round-trips it, so phase-06 only needs to start passing a
    # real value, not touch this column's plumbing.
    cursor: Mapped[str | None] = mapped_column(Text, nullable=True)
    payload_schema_version: Mapped[int] = mapped_column(Integer, nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    # SPEC-ARO-011: computed once, in domain.checkpoint.record() (not here), and
    # persisted verbatim — see PostgresCheckpointRepository.save()'s own docstring for
    # why that split keeps this adapter symmetric with the in-memory one.
    checksum: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ToolRequestRow(Base):
    __tablename__ = "tool_requests"
    __table_args__ = (
        UniqueConstraint("gateway_correlation_id", name="uq_tool_requests_gateway_correlation_id"),
        UniqueConstraint("agent_task_id", "idempotency_key", name="uq_tool_requests_agent_task_idempotency_key"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    workflow_instance_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.workflow_instances.id"), nullable=False
    )
    agent_task_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.agent_tasks.id"), nullable=False)
    # Beyond 07-data-model's minimum list, but essential: 02-business-invariants
    # §"Tool Gateway Boundary" requires a checkpoint before every tool
    # dispatch, and this FK makes a checkpoint-less row impossible to insert.
    preceding_checkpoint_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.checkpoints.id"), nullable=False
    )
    tool_name: Mapped[str] = mapped_column(String(200), nullable=False)
    # SPEC-ARO-017 01-domain-model/07-data-model: Tool Request's own minimal fields,
    # wired end to end by that spec. capability/idempotency_key are populated by
    # RequestToolService today (idempotency_key straight from the command's own
    # idempotencyKey — the same value CommandIdempotencyGuard already keys the whole
    # command's replay cache on). gateway_correlation_id/policy_snapshot_json stay
    # nullable/unpopulated until SPEC-ARO-019 (dispatching through the Tool Gateway is
    # exactly where a real correlation id gets assigned and a policy snapshot taken).
    capability: Mapped[str | None] = mapped_column(String(200), nullable=True)
    gateway_correlation_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    policy_snapshot_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    idempotency_key: Mapped[str | None] = mapped_column(String(200), nullable=True)
    state: Mapped[str] = mapped_column(String(40), nullable=False)
    input_payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    # SPEC-ARO-017: round-trip wired; still unpopulated until SPEC-ARO-020 consumes a
    # real tool.completed/tool.failed event.
    result_payload_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # Computed by the repository adapter once state reaches a terminal
    # ToolRequestStatus — see repositories.py.
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class ProcessedEventRow(Base):
    __tablename__ = "processed_events"

    event_id: Mapped[str] = mapped_column(String(200), primary_key=True)
    consumer_name: Mapped[str] = mapped_column(String(100), primary_key=True)
    event_type: Mapped[str | None] = mapped_column(String(200), nullable=True)
    workflow_instance_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # 07-data-model column; reconciliation replay-safety hash (phase-08
    # failure-recovery-reconciliation).
    result_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)


class PoisonEventRow(Base):
    """SPEC-ARO-024 10-failure-handling §"Poison Event". Not (event_id, consumer_name)
    keyed like processed_events — a poisoned delivery may be recorded more than once
    across replays, so `id` is its own surrogate primary key.
    """

    __tablename__ = "poison_events"
    __table_args__ = (Index("ix_poison_events_recorded_at", "recorded_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    event_id: Mapped[str] = mapped_column(String(200), nullable=False)
    consumer_name: Mapped[str] = mapped_column(String(100), nullable=False)
    event_type: Mapped[str] = mapped_column(String(200), nullable=False)
    payload: Mapped[str] = mapped_column(Text, nullable=False)
    error_message: Mapped[str] = mapped_column(Text, nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined" — NULL
    # until an operator flags this row as already triaged; a one-way flag, not a status
    # machine (see this column's own migration docstring).
    quarantined_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class AuditEventRow(Base):
    """SPEC-ARO-034 12-observability §"Audit Events": "审计事件必须可长期保存." Append-only,
    like PoisonEventRow — `id` is its own surrogate primary key (no natural composite
    key: many audit rows can legitimately share the same workflow_instance_id/action).
    """

    __tablename__ = "audit_events"
    __table_args__ = (
        Index("ix_audit_events_workflow_instance_occurred", "workflow_instance_id", "occurred_at"),
        Index("ix_audit_events_occurred_at", "occurred_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    audit_type: Mapped[str] = mapped_column(String(40), nullable=False)
    action: Mapped[str] = mapped_column(String(100), nullable=False)
    resource_type: Mapped[str] = mapped_column(String(40), nullable=False)
    resource_id: Mapped[str] = mapped_column(String(100), nullable=False)
    workflow_instance_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    ticket_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    actor_type: Mapped[str] = mapped_column(String(40), nullable=False)
    actor_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    outcome: Mapped[str] = mapped_column(String(20), nullable=False)
    correlation_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    causation_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    detail: Mapped[str] = mapped_column(Text, nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class OutboxEventRow(Base):
    __tablename__ = "outbox_events"
    __table_args__ = (Index("ix_outbox_events_status_available_at", "status", "available_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    aggregate_type: Mapped[str] = mapped_column(String(100), nullable=False)
    aggregate_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    # Beyond 07-data-model's minimum list, kept as first-class columns (not
    # just inside payload_json) purely for cheap operational querying.
    workflow_instance_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    ticket_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    event_type: Mapped[str] = mapped_column(String(200), nullable=False)
    schema_version: Mapped[int] = mapped_column(Integer, nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    correlation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    causation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    # 08-transaction-and-outbox §"Outbox Publisher": "Mark published_at after success.
    # Move to DEAD_LETTER after repeated failures" — DispatchOutboxEventsService owns
    # the PENDING -> PUBLISHED / DEAD_LETTER transitions; real broker wiring (RabbitMQ)
    # is phase-07 (runtime-event-publishing).
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    # attempts / available_at implement "Support retry/backoff": a failed publish
    # increments attempts and pushes available_at forward instead of retrying
    # immediately. Beyond 07-data-model's minimum column list, but required by
    # 08-transaction-and-outbox's explicit publisher requirements.
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    available_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CommandIdempotencyRow(Base):
    """07-data-model §"command_idempotency" / 09-concurrency-and-idempotency
    §"Command Idempotency": every one of Start/Pause/Resume/CompleteTask/RequestTool
    goes through this table via
    agentruntime.application.services.idempotency.CommandIdempotencyGuard.
    """

    __tablename__ = "command_idempotency"

    idempotency_key: Mapped[str] = mapped_column(String(200), primary_key=True)
    command_type: Mapped[str] = mapped_column(String(100), nullable=False)
    target_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    request_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    response_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
