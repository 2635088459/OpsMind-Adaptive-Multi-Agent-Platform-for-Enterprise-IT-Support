"""add grading fields to case execution results

SPEC-EI-014/015 (deterministic-grader-registry / safety-policy-compliance-graders):
infrastructure.graders.deterministic's own PolicyComplianceGrader/
ResolutionSuccessGrader/ToolArgumentSchemaGrader need runner-reported approval and
verification outcomes, and per-tool call arguments. SPEC-EI-016
(quality-llm-judge-graders): infrastructure.graders.llm_judge's own
AnthropicQualityJudge needs a free-text explanation to actually judge. None of these
`evaluation_case_execution_results` carried before this — see
application.records.CaseExecutionResult's own docstring.

Revision ID: d7a1e5c93f26
Revises: c2f9a6e4d8b1
Create Date: 2026-08-28
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "d7a1e5c93f26"
down_revision: str | None = "c2f9a6e4d8b1"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"
_TABLE = "evaluation_case_execution_results"


def upgrade() -> None:
    op.add_column(_TABLE, sa.Column("approval_triggered", sa.Boolean(), nullable=False, server_default=sa.false()), schema=SCHEMA)
    op.add_column(_TABLE, sa.Column("verification_passed", sa.Boolean(), nullable=False, server_default=sa.true()), schema=SCHEMA)
    op.add_column(
        _TABLE, sa.Column("tool_call_args_json", postgresql.JSONB(), nullable=False, server_default="{}"), schema=SCHEMA,
    )
    op.add_column(_TABLE, sa.Column("explanation_text", sa.Text(), nullable=False, server_default=""), schema=SCHEMA)


def downgrade() -> None:
    op.drop_column(_TABLE, "explanation_text", schema=SCHEMA)
    op.drop_column(_TABLE, "tool_call_args_json", schema=SCHEMA)
    op.drop_column(_TABLE, "verification_passed", schema=SCHEMA)
    op.drop_column(_TABLE, "approval_triggered", schema=SCHEMA)
