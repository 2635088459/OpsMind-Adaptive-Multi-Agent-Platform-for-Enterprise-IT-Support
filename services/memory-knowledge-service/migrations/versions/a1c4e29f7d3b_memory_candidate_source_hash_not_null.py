"""memory_candidates.source_hash not null

SPEC-MK-010/011 07-data-model `memory.memory_candidates`: "唯一键：source_hash,
memory_type" ("not null" per the LLD table) — ExtractMemoryCandidateService now
always computes source_hash before persisting (previously no service populated it,
so the column stayed nullable as a documented placeholder; see
MemoryCandidateRow.source_hash's own prior comment). Same nullable-then-NOT-NULL
sequence as f5e0379f817f's own command_idempotency columns — SPEC-MK-001/002's
memory_candidates table has no production data yet (phase-00/03, not released), so
the backfill is a no-op in practice.

Revision ID: a1c4e29f7d3b
Revises: f5e0379f817f
Create Date: 2026-08-16
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "a1c4e29f7d3b"
down_revision: str | None = "f5e0379f817f"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "memory"


def upgrade() -> None:
    op.execute(f"UPDATE {SCHEMA}.memory_candidates SET source_hash = '' WHERE source_hash IS NULL")
    op.alter_column("memory_candidates", "source_hash", nullable=False, schema=SCHEMA)


def downgrade() -> None:
    op.alter_column("memory_candidates", "source_hash", nullable=True, schema=SCHEMA)
