"""create agent runtime schema

SPEC-ARO-002 / 07-data-model: dedicated `agent_runtime` schema plus its seven
tables (workflow_instances, agent_tasks, checkpoints, tool_requests,
processed_events, outbox_events, command_idempotency). Mirrors the sibling
ticket-workflow-service's V001__create_ticket_schema.sql pattern: one shared
Postgres database, one schema per service, never mixed.

Revision ID: b94bbf56912f
Revises:
Create Date: 2026-08-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "b94bbf56912f"
down_revision: str | None = None
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.execute(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")

    op.create_table(
        "workflow_instances",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("ticket_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("ticket_cycle_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("workflow_type", sa.String(100), nullable=False),
        sa.Column("definition_id", sa.String(200), nullable=False),
        sa.Column("definition_version", sa.Integer, nullable=False),
        sa.Column("state", sa.String(40), nullable=False),
        sa.Column("workflow_version", sa.BigInteger, nullable=False),
        sa.Column("pause_generation", sa.Integer, nullable=False, server_default="0"),
        sa.Column("current_checkpoint_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("last_pause_idempotency_key", sa.String(200), nullable=True),
        sa.Column("last_resume_idempotency_key", sa.String(200), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        schema=SCHEMA,
    )
    # 02-business-invariants §"Workflow Instance Invariants": "At most one
    # active Workflow Instance may exist for the same ticketId + ticketCycleId
    # + workflowType" — a partial unique index, since a plain UNIQUE
    # constraint can't express "only while non-terminal".
    op.execute(
        f"""
        CREATE UNIQUE INDEX uq_workflow_instances_active
        ON {SCHEMA}.workflow_instances (ticket_id, ticket_cycle_id, workflow_type)
        WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
        """
    )

    op.create_table(
        "agent_tasks",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "workflow_instance_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey(f"{SCHEMA}.workflow_instances.id"),
            nullable=False,
        ),
        sa.Column("task_key", sa.String(200), nullable=False),
        sa.Column("agent_role", sa.String(100), nullable=True),
        sa.Column("task_type", sa.String(200), nullable=False),
        sa.Column("state", sa.String(40), nullable=False),
        sa.Column("depends_on_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("input_payload_json", sa.Text, nullable=True),
        sa.Column("result_payload_json", sa.Text, nullable=True),
        sa.Column("failure_reason", sa.Text, nullable=True),
        sa.Column("attempt", sa.Integer, nullable=False, server_default="1"),
        sa.Column("max_attempts", sa.Integer, nullable=False, server_default="1"),
        sa.Column("claim_owner", sa.String(200), nullable=True),
        sa.Column("claim_token", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("claim_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("pause_generation", sa.Integer, nullable=False, server_default="0"),
        sa.Column("task_version", sa.BigInteger, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("workflow_instance_id", "task_key", name="uq_agent_tasks_workflow_instance_task_key"),
        schema=SCHEMA,
    )
    op.create_index(
        "ix_agent_tasks_workflow_instance_state", "agent_tasks", ["workflow_instance_id", "state"], schema=SCHEMA
    )
    op.create_index(
        "ix_agent_tasks_role_state_claim_expiry", "agent_tasks", ["agent_role", "state", "claim_expires_at"], schema=SCHEMA
    )

    op.create_table(
        "checkpoints",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "workflow_instance_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey(f"{SCHEMA}.workflow_instances.id"),
            nullable=False,
        ),
        sa.Column("workflow_version", sa.BigInteger, nullable=True),
        sa.Column("checkpoint_type", sa.String(40), nullable=False),
        sa.Column("cursor", sa.Text, nullable=True),
        sa.Column("payload_schema_version", sa.Integer, nullable=False),
        sa.Column("payload_json", sa.Text, nullable=False),
        sa.Column("checksum", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index(
        "ix_checkpoints_workflow_instance_created_desc",
        "checkpoints",
        ["workflow_instance_id", sa.text("created_at DESC")],
        schema=SCHEMA,
    )
    op.create_index(
        "ix_checkpoints_workflow_instance_version", "checkpoints", ["workflow_instance_id", "workflow_version"], schema=SCHEMA
    )

    op.create_table(
        "tool_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "workflow_instance_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey(f"{SCHEMA}.workflow_instances.id"),
            nullable=False,
        ),
        sa.Column("agent_task_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.agent_tasks.id"), nullable=False),
        sa.Column("preceding_checkpoint_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.checkpoints.id"), nullable=False),
        sa.Column("tool_name", sa.String(200), nullable=False),
        sa.Column("capability", sa.String(200), nullable=True),
        sa.Column("gateway_correlation_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("policy_snapshot_json", sa.Text, nullable=True),
        sa.Column("idempotency_key", sa.String(200), nullable=True),
        sa.Column("state", sa.String(40), nullable=False),
        sa.Column("input_payload_json", sa.Text, nullable=False),
        sa.Column("result_payload_json", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint("gateway_correlation_id", name="uq_tool_requests_gateway_correlation_id"),
        sa.UniqueConstraint("agent_task_id", "idempotency_key", name="uq_tool_requests_agent_task_idempotency_key"),
        schema=SCHEMA,
    )

    op.create_table(
        "processed_events",
        sa.Column("event_id", sa.String(200), primary_key=True),
        sa.Column("consumer_name", sa.String(100), primary_key=True),
        sa.Column("event_type", sa.String(200), nullable=True),
        sa.Column("workflow_instance_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("result_hash", sa.String(128), nullable=True),
        schema=SCHEMA,
    )

    op.create_table(
        "outbox_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("aggregate_type", sa.String(100), nullable=False),
        sa.Column("aggregate_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("workflow_instance_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("ticket_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("event_type", sa.String(200), nullable=False),
        sa.Column("schema_version", sa.Integer, nullable=False),
        sa.Column("payload_json", sa.Text, nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("causation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="PENDING"),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_outbox_events_status_available_at", "outbox_events", ["status", "available_at"], schema=SCHEMA)

    op.create_table(
        "command_idempotency",
        sa.Column("idempotency_key", sa.String(200), primary_key=True),
        sa.Column("command_type", sa.String(100), nullable=False),
        sa.Column("target_id", sa.String(200), nullable=True),
        sa.Column("request_hash", sa.String(128), nullable=True),
        sa.Column("response_json", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("command_idempotency", schema=SCHEMA)
    op.drop_table("outbox_events", schema=SCHEMA)
    op.drop_table("processed_events", schema=SCHEMA)
    op.drop_table("tool_requests", schema=SCHEMA)
    op.drop_table("checkpoints", schema=SCHEMA)
    op.drop_table("agent_tasks", schema=SCHEMA)
    op.drop_table("workflow_instances", schema=SCHEMA)
    op.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
