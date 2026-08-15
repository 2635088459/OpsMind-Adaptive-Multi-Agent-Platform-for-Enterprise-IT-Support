"""audit events table

SPEC-ARO-034 (12-observability §"Audit Events"): "审计事件必须可长期保存: workflow
transition, task transition, checkpoint created, tool request created, external
event consumed, pause/resume, recovery decision, admin intervention." Append-only,
mirroring poison_events' own "id is its own surrogate primary key" shape — many rows
can legitimately share the same workflow_instance_id/action, so no natural composite
key exists.

Revision ID: f1a2b3c4d5e6
Revises: a3f7c9e1b204
Create Date: 2026-08-16 09:00:00.000000

"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "f1a2b3c4d5e6"
down_revision: str | None = "a3f7c9e1b204"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.create_table(
        "audit_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("audit_type", sa.String(40), nullable=False),
        sa.Column("action", sa.String(100), nullable=False),
        sa.Column("resource_type", sa.String(40), nullable=False),
        sa.Column("resource_id", sa.String(100), nullable=False),
        sa.Column("workflow_instance_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("ticket_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("actor_type", sa.String(40), nullable=False),
        sa.Column("actor_id", sa.String(200), nullable=True),
        sa.Column("outcome", sa.String(20), nullable=False),
        sa.Column("correlation_id", sa.String(100), nullable=True),
        sa.Column("causation_id", sa.String(100), nullable=True),
        sa.Column("detail", sa.Text, nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index(
        "ix_audit_events_workflow_instance_occurred", "audit_events",
        ["workflow_instance_id", "occurred_at"], schema=SCHEMA,
    )
    op.create_index("ix_audit_events_occurred_at", "audit_events", ["occurred_at"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_index("ix_audit_events_occurred_at", table_name="audit_events", schema=SCHEMA)
    op.drop_index("ix_audit_events_workflow_instance_occurred", table_name="audit_events", schema=SCHEMA)
    op.drop_table("audit_events", schema=SCHEMA)
