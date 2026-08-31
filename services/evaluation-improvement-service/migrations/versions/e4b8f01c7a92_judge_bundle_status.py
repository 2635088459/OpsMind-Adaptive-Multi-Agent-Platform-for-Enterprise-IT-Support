"""create judge bundle status table

SPEC-EI-018 (judge-calibration-drift-guard) / 10-failure-handling §"Judge drift": "同一
judge bundle 对固定 calibration set 超出阈值时禁用该 bundle." infrastructure.graders.registry.
GraderRegistry reads this table (via PostgresJudgeBundleStatusRepository) before ever
invoking an LLM_JUDGE grader; EvaluateJudgeCalibrationService is the only writer.

Revision ID: e4b8f01c7a92
Revises: d7a1e5c93f26
Create Date: 2026-08-28
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "e4b8f01c7a92"
down_revision: str | None = "d7a1e5c93f26"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.create_table(
        "evaluation_judge_bundle_status",
        sa.Column("grader_version", sa.String(100), primary_key=True),
        sa.Column("enabled", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("last_checked_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_mean_absolute_error", sa.Numeric, nullable=True),
        sa.Column("disabled_reason", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("evaluation_judge_bundle_status", schema=SCHEMA)
