"""poison events

SPEC-MK-029 10-failure-handling §"Poison Event": "写入 poison event 表" — a separate
table from processed_events/outbox_events, parked for manual investigation and
possible replay (05-api-contracts §"Admin API": "mark poison event quarantined").
Mirrors agent-runtime-service's own SPEC-ARO-024/031 poison_events migration.

Revision ID: c7b3f1a9d24e
Revises: a1c4e29f7d3b
Create Date: 2026-08-17
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "c7b3f1a9d24e"
down_revision: str | None = "a1c4e29f7d3b"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "memory"


def upgrade() -> None:
    op.create_table(
        "poison_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_id", sa.String(200), nullable=False),
        sa.Column("consumer_name", sa.String(100), nullable=False),
        sa.Column("event_type", sa.String(200), nullable=False),
        sa.Column("payload_json", sa.Text, nullable=False),
        sa.Column("error_message", sa.Text, nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("quarantined_at", sa.DateTime(timezone=True), nullable=True),
        schema=SCHEMA,
    )
    op.create_index("ix_poison_events_recorded_at", "poison_events", ["recorded_at"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_table("poison_events", schema=SCHEMA)
