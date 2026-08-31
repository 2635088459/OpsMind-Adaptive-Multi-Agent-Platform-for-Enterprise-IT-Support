"""add benchmark_run_id to improvement candidates

SPEC-EI-025 (candidate-benchmark-binding-gate-enforcement) / phase-05 own "强制约束":
"Candidate 必须绑定 source failures、source run、benchmark result 和 gate report."
`improvement_candidates` previously carried only a bare `benchmark_passed` boolean with
nothing binding it to the actual EvaluationRun that produced it — a caller could claim
any outcome. This column is the FK to that run; CreateImprovementCandidateService now
derives `benchmark_passed` from the bound run's own terminal PASSED/FAILED release-gate
status instead of trusting an unverified claim.

Revision ID: f6c3a9e1b7d4
Revises: e4b8f01c7a92
Create Date: 2026-08-28
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "f6c3a9e1b7d4"
down_revision: str | None = "e4b8f01c7a92"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"
_TABLE = "improvement_candidates"


def upgrade() -> None:
    op.add_column(_TABLE, sa.Column("benchmark_run_id", postgresql.UUID(as_uuid=True), nullable=True), schema=SCHEMA)
    op.create_foreign_key(
        "fk_improvement_candidates_benchmark_run_id", _TABLE, "evaluation_runs", ["benchmark_run_id"], ["id"],
        source_schema=SCHEMA, referent_schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_constraint("fk_improvement_candidates_benchmark_run_id", _TABLE, schema=SCHEMA, type_="foreignkey")
    op.drop_column(_TABLE, "benchmark_run_id", schema=SCHEMA)
