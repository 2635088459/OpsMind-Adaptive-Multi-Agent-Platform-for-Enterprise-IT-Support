"""active_component_configs table

The agent-runtime side of the platform's "improvement is evaluation-gated" loop:
one row per tunable component, holding the version adopted from an
`improvement.promoted.v1` event. A promotion replaces the row (component PK); a
matching `improvement.rollback.requested.v1` deletes it, reverting that
component to its built-in default.

Revision ID: a1b2c3d4e5f6
Revises: 9d3b6c2e5a71
Create Date: 2026-09-08 05:00:00.000000

"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "a1b2c3d4e5f6"
down_revision: str | None = "9d3b6c2e5a71"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.create_table(
        "active_component_configs",
        sa.Column("component", sa.String(length=200), primary_key=True),
        sa.Column("version", sa.String(length=200), nullable=False),
        sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("source_candidate_id", sa.String(length=200), nullable=False),
        sa.Column("activated_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("active_component_configs", schema=SCHEMA)
