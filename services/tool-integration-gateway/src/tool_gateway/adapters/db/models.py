"""SPEC-TG-002 / 07-data-model: SQLAlchemy ORM models for the `tool` PostgreSQL
schema. This module is the entire ORM boundary — application and domain code
never see these classes (enforced by import-linter's "Application must not
depend on adapters" contract); adapters.db.postgres_repositories translates
between these rows and the application-layer domain objects/records. Mirrors
memory-knowledge-service's own SPEC-MK-002
infrastructure/persistence/postgres/models.py convention exactly, adjusted for
this domain's own flat adapters/db/ layout (no infrastructure/persistence
nesting).

Some columns exist here because 07-data-model lists them but no current
application service populates them yet (`credential_bindings` in full — see its
own class docstring below) — those stay unused until the spec that owns the
behavior lands (phase-03 SPEC-TG-012 "credential-binding-invocation-preparation"),
mirroring memory-knowledge-service's own SPEC-MK-002 models.py precedent for the
same situation.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, MetaData, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# technology-baseline §7 "Data and Infrastructure": "tool.*  → Tool Gateway".
SCHEMA = "tool"


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA)


class ToolRequestRow(Base):
    __tablename__ = "tool_requests"
    __table_args__ = (
        # 07-data-model `tool_requests` §"Unique key":
        # (workflow_instance_id, agent_task_id, idempotency_key).
        UniqueConstraint(
            "workflow_instance_id", "agent_task_id", "idempotency_key", name="uq_tool_requests_workflow_task_idempotency_key",
        ),
        Index("ix_tool_requests_status_created_at", "status", "created_at"),
        Index("ix_tool_requests_ticket_id_ticket_cycle_id", "ticket_id", "ticket_cycle_id"),
        Index("ix_tool_requests_workflow_instance_id_agent_task_id", "workflow_instance_id", "agent_task_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    idempotency_key: Mapped[str] = mapped_column(String(200), nullable=False)
    payload_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    ticket_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    ticket_cycle_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    workflow_instance_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    agent_task_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    requested_by_type: Mapped[str] = mapped_column(String(20), nullable=False)
    requested_by_id: Mapped[str] = mapped_column(String(200), nullable=False)
    capability_name: Mapped[str] = mapped_column(String(200), nullable=False)
    tool_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    input_payload_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    risk_decision_ref_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    approval_request_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    # SPEC-TG-006 / INV-TG-008: "Tool Request records the schema version used
    # at submission time." Not in SPEC-TG-002's own original column list —
    # added by the migration SPEC-TG-006 introduces (see migrations/versions/
    # *_tool_requests_resolved_connector_binding.py).
    resolved_connector_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    resolved_connector_version: Mapped[str | None] = mapped_column(String(40), nullable=True)
    result_envelope_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    denial_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # SPEC-TG-016: retry *scheduling* backoff — not in 07-data-model's own
    # column list (predates retry scheduling; see domain.tool_request.
    # ToolRequest.retry_not_before's own docstring for the precedent this
    # follows, same as CredentialBindingRow.created_at SPEC-TG-012).
    retry_not_before: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class ToolExecutionRow(Base):
    __tablename__ = "tool_executions"
    __table_args__ = (
        UniqueConstraint("tool_request_id", "attempt_number", name="uq_tool_executions_tool_request_attempt"),
        # 07-data-model `tool_executions` §"Unique keys":
        # (connector_id, operation_key) — INV-TG-003's side-effect idempotency
        # key, enforced at the schema level (nullable so read-only attempts,
        # which carry no operation_key, never collide with each other).
        UniqueConstraint("connector_id", "operation_key", name="uq_tool_executions_connector_operation_key"),
        Index("ix_tool_executions_status_lease_expires_at", "status", "lease_expires_at"),
        Index("ix_tool_executions_tool_request_id_attempt_number", "tool_request_id", "attempt_number"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    tool_request_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.tool_requests.id"), nullable=False)
    attempt_number: Mapped[int] = mapped_column(Integer, nullable=False)
    connector_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    connector_version: Mapped[str] = mapped_column(String(40), nullable=False)
    operation_key: Mapped[str | None] = mapped_column(String(500), nullable=True)
    # Not in 07-data-model's own column list — a required domain.tool_execution.
    # ToolExecution field (SideEffectKind) with no default, added here the same
    # way memory-knowledge-service's own models.py adds columns 07-data-model
    # never named when a domain field needs one to round-trip at all.
    side_effect_kind: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    lease_owner: Mapped[str | None] = mapped_column(String(200), nullable=True)
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    timeout_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    error_code: Mapped[str | None] = mapped_column(String(100), nullable=True)
    retryable: Mapped[bool] = mapped_column(nullable=False, default=False)
    result_envelope_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)


class ToolConnectorRow(Base):
    __tablename__ = "tool_connectors"
    __table_args__ = (UniqueConstraint("id", "version", name="uq_tool_connectors_id_version"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    version: Mapped[str] = mapped_column(String(40), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    manifest_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    capabilities_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    input_schema: Mapped[str] = mapped_column(String(500), nullable=False)
    output_schema: Mapped[str] = mapped_column(String(500), nullable=False)
    risk_level: Mapped[str] = mapped_column(String(20), nullable=False)
    requires_approval: Mapped[bool] = mapped_column(nullable=False, default=False)
    secret_requirements_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    network_policy_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    timeout_policy_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    retry_policy_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ToolResultRow(Base):
    __tablename__ = "tool_results"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    execution_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.tool_executions.id"), nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    summary: Mapped[str] = mapped_column(Text, nullable=False)
    structured_output_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    raw_output_ref: Mapped[str | None] = mapped_column(String(500), nullable=True)
    redaction_status: Mapped[str] = mapped_column(String(30), nullable=False)
    evidence_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    external_resource_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    error_code: Mapped[str | None] = mapped_column(String(100), nullable=True)
    retryable: Mapped[bool] = mapped_column(nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CredentialBindingRow(Base):
    """07-data-model `credential_bindings`. SPEC-TG-001's own deferral note
    ("no current application service writes to this table") was closed by
    SPEC-TG-012 — ``adapters.db.postgres_repositories.PostgresCredentialBindingRepository``
    is the first repository to actually read/write it.
    ``created_at`` is not itself in 07-data-model's own column list (only
    listed: credential_binding_id, connector_id, tenant_id, scope, vault_ref,
    rotation_version, status, last_used_at) — added the same way every other
    domain entity's row in this schema records when it was created, rather
    than reconstructing a lossy placeholder from ``last_used_at`` on reload.
    """

    __tablename__ = "credential_bindings"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    connector_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    tenant_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    scope: Mapped[str] = mapped_column(String(200), nullable=False)
    vault_ref: Mapped[str] = mapped_column(String(500), nullable=False)
    rotation_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="ACTIVE")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class ToolAuditRecordRow(Base):
    __tablename__ = "tool_audit_records"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    actor_type: Mapped[str | None] = mapped_column(String(20), nullable=True)
    actor_id: Mapped[str] = mapped_column(String(200), nullable=False)
    action: Mapped[str] = mapped_column(String(100), nullable=False)
    tool_request_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    execution_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    connector_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    resource_type: Mapped[str] = mapped_column(String(50), nullable=False)
    resource_id: Mapped[str] = mapped_column(String(200), nullable=False)
    outcome: Mapped[str] = mapped_column(String(50), nullable=False)
    correlation_id: Mapped[str] = mapped_column(String(200), nullable=False)
    metadata_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class OutboxEventRow(Base):
    __tablename__ = "outbox_events"
    __table_args__ = (Index("ix_outbox_events_status_available_at", "status", "available_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    aggregate_type: Mapped[str] = mapped_column(String(50), nullable=False)
    aggregate_id: Mapped[str] = mapped_column(String(200), nullable=False)
    event_type: Mapped[str] = mapped_column(String(100), nullable=False)
    event_version: Mapped[str] = mapped_column(String(10), nullable=False)
    payload_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    headers_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    correlation_id: Mapped[str] = mapped_column(String(200), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    attempt_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    available_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ProcessedEventRow(Base):
    __tablename__ = "processed_events"
    __table_args__ = (UniqueConstraint("event_id", "consumer_name", name="uq_processed_events_event_id_consumer_name"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    event_id: Mapped[str] = mapped_column(String(200), nullable=False)
    consumer_name: Mapped[str] = mapped_column(String(200), nullable=False)
    event_type: Mapped[str | None] = mapped_column(String(100), nullable=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
