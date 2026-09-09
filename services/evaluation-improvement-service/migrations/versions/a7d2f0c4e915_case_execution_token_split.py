"""case execution result: prompt/completion token split

SPEC-EI-013 follow-up. `evaluation_case_execution_results` carried a single
`cost_tokens` total. The real agent-runtime evaluation execute-case endpoint now
returns a genuine prompt/completion split from the LLM call's usage, and
SdkLangSmithExperimentAdapter forwards it to LangSmith (project token stats show
input vs output). These two columns hold it; both default 0 for rows written by the
simulator's older single-total path.

Revision ID: a7d2f0c4e915
Revises: e1c7a9d4b206
Create Date: 2026-09-09
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "a7d2f0c4e915"
down_revision: str | None = "e1c7a9d4b206"
branch_labels: Sequence[str] | None = None
depends_on: str | None = None

_SCHEMA = "evaluation"
_TABLE = "evaluation_case_execution_results"


def upgrade() -> None:
    op.add_column(_TABLE, sa.Column("prompt_tokens", sa.Integer(), nullable=False, server_default="0"), schema=_SCHEMA)
    op.add_column(_TABLE, sa.Column("completion_tokens", sa.Integer(), nullable=False, server_default="0"), schema=_SCHEMA)


def downgrade() -> None:
    op.drop_column(_TABLE, "completion_tokens", schema=_SCHEMA)
    op.drop_column(_TABLE, "prompt_tokens", schema=_SCHEMA)
