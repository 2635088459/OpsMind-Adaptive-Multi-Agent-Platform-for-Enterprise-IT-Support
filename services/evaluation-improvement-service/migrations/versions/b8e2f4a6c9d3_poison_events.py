"""create poison events table

SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling
§"Poison Event": "记录 poison event 表" — not in 07-data-model's own literal table
list, a pragmatic extension the same way `evaluation_case_execution_results` was.

Revision ID: b8e2f4a6c9d3
Revises: a3d9e2f5c186
Create Date: 2026-08-29
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "b8e2f4a6c9d3"
down_revision: str | None = "a3d9e2f5c186"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.create_table(
        "evaluation_poison_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_id", sa.String(200), nullable=False),
        sa.Column("consumer_name", sa.String(200), nullable=False),
        sa.Column("event_type", sa.String(100), nullable=False),
        sa.Column("payload", sa.Text(), nullable=False),
        sa.Column("error_message", sa.Text(), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_evaluation_poison_events_recorded_at", "evaluation_poison_events", ["recorded_at"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_index("ix_evaluation_poison_events_recorded_at", table_name="evaluation_poison_events", schema=SCHEMA)
    op.drop_table("evaluation_poison_events", schema=SCHEMA)
