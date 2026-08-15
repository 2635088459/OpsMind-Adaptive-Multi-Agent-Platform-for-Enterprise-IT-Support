"""poison events table

SPEC-ARO-024 (10-failure-handling §"Poison Event"): "事件无法反序列化、schema 缺字段或
违反不变量时: 1. 写入 poison event 表或 dead letter." A malformed/unparsable runtime
event payload is recorded here rather than in processed_events, so a corrected replay
under the same eventId is not permanently blocked by the dedup gate.

Revision ID: d5d7cffa8bb5
Revises: d2acf6d67e2e
Create Date: 2026-08-12 21:00:00.000000

"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "d5d7cffa8bb5"
down_revision: str | None = "d2acf6d67e2e"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.create_table(
        "poison_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_id", sa.String(200), nullable=False),
        sa.Column("consumer_name", sa.String(100), nullable=False),
        sa.Column("event_type", sa.String(200), nullable=False),
        sa.Column("payload", sa.Text, nullable=False),
        sa.Column("error_message", sa.Text, nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_poison_events_recorded_at", "poison_events", ["recorded_at"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_index("ix_poison_events_recorded_at", table_name="poison_events", schema=SCHEMA)
    op.drop_table("poison_events", schema=SCHEMA)
