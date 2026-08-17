"""outbox, idempotency and audit baseline

SPEC-MK-003 / 09-concurrency-and-idempotency, 12-observability: reshapes
command_idempotency to support real request-hash conflict detection
(CommandIdempotencyGuard) — drops the SPEC-MK-002 placeholder `result_ref` column,
adds `target_id`, `request_hash`, `response_json`, `expires_at` — and creates the new
`audit_events` table. Mirrors agent-runtime-service's own SPEC-ARO-003 migration
f958dd5078b1 (which similarly reshaped command_idempotency and added an outbox
column) and its separate audit_events migration f1a2b3c4d5e6.

Revision ID: f5e0379f817f
Revises: 55e46d26b7fa
Create Date: 2026-08-16
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "f5e0379f817f"
down_revision: str | None = "55e46d26b7fa"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "memory"


def upgrade() -> None:
    op.drop_column("command_idempotency", "result_ref", schema=SCHEMA)
    op.add_column("command_idempotency", sa.Column("target_id", sa.String(200), nullable=True), schema=SCHEMA)
    # request_hash/response_json are added nullable first, backfilled, then tightened —
    # SPEC-MK-002's command_idempotency table has no production data yet (phase-00, not
    # released), so an empty-table environment backfills nothing in practice; the
    # nullable-then-NOT-NULL sequence is still the safe general pattern.
    op.add_column("command_idempotency", sa.Column("request_hash", sa.String(64), nullable=True), schema=SCHEMA)
    op.add_column("command_idempotency", sa.Column("response_json", sa.Text, nullable=True), schema=SCHEMA)
    op.execute(f"UPDATE {SCHEMA}.command_idempotency SET request_hash = '', response_json = '{{}}' WHERE request_hash IS NULL")
    op.alter_column("command_idempotency", "request_hash", nullable=False, schema=SCHEMA)
    op.alter_column("command_idempotency", "response_json", nullable=False, schema=SCHEMA)
    op.add_column("command_idempotency", sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True), schema=SCHEMA)

    op.create_table(
        "audit_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("audit_type", sa.String(40), nullable=False),
        sa.Column("action", sa.String(100), nullable=False),
        sa.Column("resource_type", sa.String(40), nullable=False),
        sa.Column("resource_id", sa.String(100), nullable=False),
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
    op.create_index("ix_audit_events_resource_type_resource_id", "audit_events", ["resource_type", "resource_id"], schema=SCHEMA)
    op.create_index("ix_audit_events_occurred_at", "audit_events", ["occurred_at"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_table("audit_events", schema=SCHEMA)
    op.drop_column("command_idempotency", "expires_at", schema=SCHEMA)
    op.drop_column("command_idempotency", "response_json", schema=SCHEMA)
    op.drop_column("command_idempotency", "request_hash", schema=SCHEMA)
    op.drop_column("command_idempotency", "target_id", schema=SCHEMA)
    op.add_column("command_idempotency", sa.Column("result_ref", sa.Text, nullable=False, server_default=""), schema=SCHEMA)
    op.alter_column("command_idempotency", "result_ref", server_default=None, schema=SCHEMA)
