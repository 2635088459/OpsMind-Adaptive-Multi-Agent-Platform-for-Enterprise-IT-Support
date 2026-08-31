"""create online evaluation samples table

SPEC-EI-028 (online-sample-evaluation) / 04-use-cases UC-EI-006: "07 消费...事件，
根据 sampling policy 选择 trace，脱敏后写入 online evaluation queue，对解释质量、证据完整性、
handoff completeness 和 user clarity 做延迟评分." Not in 07-data-model's own literal
table list — a pragmatic extension the same way `evaluation_case_execution_results`
was. `candidate_id` is a nullable FK (a sample may be general online monitoring, not
scoped to any one candidate's canary).

Revision ID: a3d9e2f5c186
Revises: f6c3a9e1b7d4
Create Date: 2026-08-28
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "a3d9e2f5c186"
down_revision: str | None = "f6c3a9e1b7d4"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.create_table(
        "evaluation_online_samples",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("candidate_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.improvement_candidates.id"), nullable=True),
        sa.Column("target_version", sa.String(200), nullable=False),
        sa.Column("source_event_type", sa.String(100), nullable=False),
        sa.Column("source_trace_ref", sa.String(500), nullable=False),
        sa.Column("redacted_context_json", postgresql.JSONB(), nullable=False, server_default="{}"),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("collected_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("scored_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("composite_score", sa.Numeric(), nullable=True),
        sa.Column("score_details_json", postgresql.JSONB(), nullable=False, server_default="{}"),
        sa.Column("failure_code", sa.String(30), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )
    op.create_index(
        "ix_evaluation_online_samples_status_collected_at", "evaluation_online_samples", ["status", "collected_at"],
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_index("ix_evaluation_online_samples_status_collected_at", table_name="evaluation_online_samples", schema=SCHEMA)
    op.drop_table("evaluation_online_samples", schema=SCHEMA)
