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
    # 07-data-model column; populated once a service writes checkpoints back
    # onto the owning Workflow Instance (not yet — SPEC-ARO-001's
    # RequestToolService only knows the workflow_instance_id, not the
    # repository needed to update this row).
    current_checkpoint_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # 07-data-model column; no application service reaches a terminal
    # workflow state yet (SPEC-ARO-001 never wired a CompleteWorkflowService).
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


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
    # 07-data-model column; not yet populated — see WorkflowInstanceRow.current_checkpoint_id.
    workflow_version: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    checkpoint_type: Mapped[str] = mapped_column(String(40), nullable=False)
    # 07-data-model column; recoverable-stream cursor, not yet produced by any
    # consumer (phase-06 external-event-consumption).
    cursor: Mapped[str | None] = mapped_column(Text, nullable=True)
    payload_schema_version: Mapped[int] = mapped_column(Integer, nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    # Computed by the repository adapter (sha256 of payload_json) — cheap,
    # requires no application-layer change, and gives 07-data-model's
    # `checksum` column real content immediately.
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
    # 07-data-model columns; Tool Gateway integration is phase-05.
    capability: Mapped[str | None] = mapped_column(String(200), nullable=True)
    gateway_correlation_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    policy_snapshot_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    idempotency_key: Mapped[str | None] = mapped_column(String(200), nullable=True)
    state: Mapped[str] = mapped_column(String(40), nullable=False)
    input_payload_json: Mapped[str] = mapped_column(Text, nullable=False)
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
