"""poison events quarantined_at column

SPEC-ARO-031 (05-api-contracts §"Admin API": "mark poison event quarantined"):
lets an operator flag a poison event as already triaged (investigation started/
underway) so the admin visibility surface (GET /admin/poison-events, SPEC-ARO-024)
can distinguish "seen" from "brand new" without inventing a separate status table.
NULL means never quarantined; quarantining is a one-way flag set once, not a status
machine — a poison event's only other exit is replay (resending the corrected event
under the same eventId), which happens outside this table entirely.

Revision ID: a3f7c9e1b204
Revises: d5d7cffa8bb5
Create Date: 2026-08-15 09:00:00.000000

"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "a3f7c9e1b204"
down_revision: str | None = "d5d7cffa8bb5"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.add_column("poison_events", sa.Column("quarantined_at", sa.DateTime(timezone=True), nullable=True), schema=SCHEMA)


def downgrade() -> None:
    op.drop_column("poison_events", "quarantined_at", schema=SCHEMA)
