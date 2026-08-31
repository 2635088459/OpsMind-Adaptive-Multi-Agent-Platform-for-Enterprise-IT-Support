"""case execution queue and langsmith run link tables

SPEC-EI-011 (case-runner-worker-lease-retry): `evaluation_case_execution_queue` —
the work-queue counterpart to `evaluation_case_execution_results` (created by
06670df0f457), see infrastructure.persistence.postgres.models.CaseExecutionQueueRow's
own docstring. SPEC-EI-013 (langsmith-experiment-linkage):
`evaluation_run_langsmith_links` — one row per run recording whether
LangSmithPort.link_experiment() was ever attempted and, if so, its outcome, so
EvaluateReleaseGateService can enforce 10-failure-handling's own "对离线 release
gate：fail closed" without re-calling LangSmith at gate time.

Revision ID: c2f9a6e4d8b1
Revises: b7e4a0c5f1d3
Create Date: 2026-08-27
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "c2f9a6e4d8b1"
down_revision: str | None = "b7e4a0c5f1d3"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.create_table(
        "evaluation_case_execution_queue",
        sa.Column("run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_runs.id"), primary_key=True),
        sa.Column("test_case_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_test_cases.id"), primary_key=True),
        sa.Column("run_generation", sa.Integer, nullable=False, server_default="1"),
        sa.Column("status", sa.String(20), nullable=False, server_default="PENDING"),
        sa.Column("attempt_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("next_attempt_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("leased_by", sa.String(200), nullable=True),
        sa.Column("leased_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("lease_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )
    # find_claimable()/find_expired_leases() both filter on (status, the relevant
    # timestamp) — mirrors ix_evaluation_outbox_events_status_available_at exactly.
    op.create_index(
        "ix_evaluation_case_execution_queue_status_next_attempt_at",
        "evaluation_case_execution_queue", ["status", "next_attempt_at"], schema=SCHEMA,
    )

    op.create_table(
        "evaluation_run_langsmith_links",
        sa.Column("run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_runs.id"), primary_key=True),
        sa.Column("enabled", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("experiment_ref", sa.String(500), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("evaluation_run_langsmith_links", schema=SCHEMA)
    op.drop_table("evaluation_case_execution_queue", schema=SCHEMA)
