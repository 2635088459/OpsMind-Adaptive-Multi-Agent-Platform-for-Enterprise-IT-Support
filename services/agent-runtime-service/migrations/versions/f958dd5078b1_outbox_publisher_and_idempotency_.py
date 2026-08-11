"""outbox publisher and idempotency generalization

SPEC-ARO-003 (08-transaction-and-outbox, 09-concurrency-and-idempotency):

- workflow_instances: drops last_pause_idempotency_key / last_resume_idempotency_key
  (SPEC-ARO-001's baseline dedup fields) — Pause/Resume now go through
  command_idempotency like every other idempotent command, via
  agentruntime.application.services.idempotency.CommandIdempotencyGuard.
- outbox_events: adds attempts, for the publisher's retry/backoff loop
  (08-transaction-and-outbox §"Outbox Publisher": "Support retry/backoff ... Move
  to DEAD_LETTER after repeated failures").

Revision ID: f958dd5078b1
Revises: b94bbf56912f
Create Date: 2026-08-10 20:20:28.215898

"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "f958dd5078b1"
down_revision: str | None = "b94bbf56912f"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.drop_column("workflow_instances", "last_pause_idempotency_key", schema=SCHEMA)
    op.drop_column("workflow_instances", "last_resume_idempotency_key", schema=SCHEMA)

    op.add_column("outbox_events", sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"), schema=SCHEMA)


def downgrade() -> None:
    op.drop_column("outbox_events", "attempts", schema=SCHEMA)

    op.add_column("workflow_instances", sa.Column("last_pause_idempotency_key", sa.String(200), nullable=True), schema=SCHEMA)
    op.add_column("workflow_instances", sa.Column("last_resume_idempotency_key", sa.String(200), nullable=True), schema=SCHEMA)
