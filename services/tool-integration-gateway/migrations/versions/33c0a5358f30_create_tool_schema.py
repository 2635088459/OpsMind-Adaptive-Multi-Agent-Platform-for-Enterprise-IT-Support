"""create tool schema

SPEC-TG-002 / 07-data-model: dedicated `tool` schema plus its eight tables
(tool_requests, tool_executions, tool_connectors, tool_results,
credential_bindings, tool_audit_records, outbox_events, processed_events).
Mirrors the sibling ticket-workflow-service's Flyway migration pattern and
memory-knowledge-service's/agent-runtime-service's own Alembic migrations: one
shared Postgres database, one schema per service, never mixed.

Revision ID: 33c0a5358f30
Revises:
Create Date: 2026-08-17
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "33c0a5358f30"
down_revision: str | None = None
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "tool"


def upgrade() -> None:
    op.execute(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")

    op.create_table(
        "tool_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("idempotency_key", sa.String(200), nullable=False),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("ticket_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("ticket_cycle_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("workflow_instance_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("agent_task_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("requested_by_type", sa.String(20), nullable=False),
        sa.Column("requested_by_id", sa.String(200), nullable=False),
        sa.Column("capability_name", sa.String(200), nullable=False),
        sa.Column("tool_name", sa.String(200), nullable=True),
        sa.Column("input_payload_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("reason", sa.Text, nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("risk_decision_ref_json", postgresql.JSONB, nullable=True),
        sa.Column("approval_request_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("result_envelope_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("denial_reason", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint(
            "workflow_instance_id", "agent_task_id", "idempotency_key", name="uq_tool_requests_workflow_task_idempotency_key",
        ),
        schema=SCHEMA,
    )
    op.create_index("ix_tool_requests_status_created_at", "tool_requests", ["status", "created_at"], schema=SCHEMA)
    op.create_index("ix_tool_requests_ticket_id_ticket_cycle_id", "tool_requests", ["ticket_id", "ticket_cycle_id"], schema=SCHEMA)
    op.create_index(
        "ix_tool_requests_workflow_instance_id_agent_task_id", "tool_requests", ["workflow_instance_id", "agent_task_id"], schema=SCHEMA,
    )

    op.create_table(
        "tool_executions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tool_request_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.tool_requests.id"), nullable=False),
        sa.Column("attempt_number", sa.Integer, nullable=False),
        sa.Column("connector_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("connector_version", sa.String(40), nullable=False),
        sa.Column("operation_key", sa.String(500), nullable=True),
        sa.Column("side_effect_kind", sa.String(20), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("lease_owner", sa.String(200), nullable=True),
        sa.Column("lease_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("timeout_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("error_code", sa.String(100), nullable=True),
        sa.Column("retryable", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("result_envelope_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.UniqueConstraint("tool_request_id", "attempt_number", name="uq_tool_executions_tool_request_attempt"),
        sa.UniqueConstraint("connector_id", "operation_key", name="uq_tool_executions_connector_operation_key"),
        schema=SCHEMA,
    )
    op.create_index("ix_tool_executions_status_lease_expires_at", "tool_executions", ["status", "lease_expires_at"], schema=SCHEMA)
    op.create_index(
        "ix_tool_executions_tool_request_id_attempt_number", "tool_executions", ["tool_request_id", "attempt_number"], schema=SCHEMA,
    )

    op.create_table(
        "tool_connectors",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("version", sa.String(40), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("manifest_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("capabilities_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("input_schema", sa.String(500), nullable=False),
        sa.Column("output_schema", sa.String(500), nullable=False),
        sa.Column("risk_level", sa.String(20), nullable=False),
        sa.Column("requires_approval", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("secret_requirements_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("network_policy_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("timeout_policy_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("retry_policy_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("id", "version", name="uq_tool_connectors_id_version"),
        schema=SCHEMA,
    )

    op.create_table(
        "tool_results",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("execution_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.tool_executions.id"), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("summary", sa.Text, nullable=False),
        sa.Column("structured_output_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("raw_output_ref", sa.String(500), nullable=True),
        sa.Column("redaction_status", sa.String(30), nullable=False),
        sa.Column("evidence_refs_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("external_resource_refs_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("error_code", sa.String(100), nullable=True),
        sa.Column("retryable", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )

    op.create_table(
        "credential_bindings",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("connector_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("tenant_id", sa.String(200), nullable=True),
        sa.Column("scope", sa.String(200), nullable=False),
        sa.Column("vault_ref", sa.String(500), nullable=False),
        sa.Column("rotation_version", sa.Integer, nullable=False, server_default="1"),
        sa.Column("status", sa.String(20), nullable=False, server_default="ACTIVE"),
        sa.Column("last_used_at", sa.DateTime(timezone=True), nullable=True),
        schema=SCHEMA,
    )

    op.create_table(
        "tool_audit_records",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("actor_type", sa.String(20), nullable=True),
        sa.Column("actor_id", sa.String(200), nullable=False),
        sa.Column("action", sa.String(100), nullable=False),
        sa.Column("tool_request_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("execution_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("connector_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("resource_type", sa.String(50), nullable=False),
        sa.Column("resource_id", sa.String(200), nullable=False),
        sa.Column("outcome", sa.String(50), nullable=False),
        sa.Column("correlation_id", sa.String(200), nullable=False),
        sa.Column("metadata_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )

    op.create_table(
        "outbox_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("aggregate_type", sa.String(50), nullable=False),
        sa.Column("aggregate_id", sa.String(200), nullable=False),
        sa.Column("event_type", sa.String(100), nullable=False),
        sa.Column("event_version", sa.String(10), nullable=False),
        sa.Column("payload_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("headers_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("correlation_id", sa.String(200), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="PENDING"),
        sa.Column("attempt_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_outbox_events_status_available_at", "outbox_events", ["status", "available_at"], schema=SCHEMA)

    op.create_table(
        "processed_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_id", sa.String(200), nullable=False),
        sa.Column("consumer_name", sa.String(200), nullable=False),
        sa.Column("event_type", sa.String(100), nullable=True),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("event_id", "consumer_name", name="uq_processed_events_event_id_consumer_name"),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("processed_events", schema=SCHEMA)
    op.drop_table("outbox_events", schema=SCHEMA)
    op.drop_table("tool_audit_records", schema=SCHEMA)
    op.drop_table("credential_bindings", schema=SCHEMA)
    op.drop_table("tool_results", schema=SCHEMA)
    op.drop_table("tool_connectors", schema=SCHEMA)
    op.drop_table("tool_executions", schema=SCHEMA)
    op.drop_table("tool_requests", schema=SCHEMA)
    op.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
