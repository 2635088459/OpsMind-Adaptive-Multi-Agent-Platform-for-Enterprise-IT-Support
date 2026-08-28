"""replace case_execution_result completed bool with status/failure_reason

SPEC-EI-009 / 10-failure-handling §"Partial Run": `evaluation_case_execution_results.
completed` was written on every save but never actually read/branched on anywhere —
a real COMPLETED/FAILED/SKIPPED status now genuinely drives score_case()'s own
eligibility guard and finalize_scoring()'s own completeness check. See
infrastructure.persistence.postgres.models.CaseExecutionResultRow's own docstring.

Revision ID: b7e4a0c5f1d3
Revises: a1c9f3d7e2b4
Create Date: 2026-08-26
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "b7e4a0c5f1d3"
down_revision: str | None = "a1c9f3d7e2b4"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.add_column(
        "evaluation_case_execution_results",
        sa.Column("status", sa.String(20), nullable=False, server_default="COMPLETED"),
        schema=SCHEMA,
    )
    op.add_column(
        "evaluation_case_execution_results", sa.Column("failure_reason", sa.Text(), nullable=True), schema=SCHEMA,
    )
    op.execute(f"UPDATE {SCHEMA}.evaluation_case_execution_results SET status = 'FAILED' WHERE completed = false")
    op.drop_column("evaluation_case_execution_results", "completed", schema=SCHEMA)


def downgrade() -> None:
    op.add_column(
        "evaluation_case_execution_results", sa.Column("completed", sa.Boolean(), nullable=False, server_default="true"),
        schema=SCHEMA,
    )
    op.execute(f"UPDATE {SCHEMA}.evaluation_case_execution_results SET completed = false WHERE status != 'COMPLETED'")
    op.drop_column("evaluation_case_execution_results", "failure_reason", schema=SCHEMA)
    op.drop_column("evaluation_case_execution_results", "status", schema=SCHEMA)
