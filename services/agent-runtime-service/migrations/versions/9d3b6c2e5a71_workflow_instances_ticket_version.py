"""workflow instances ticket version and display id

SPEC-ARO-041 (phase-10 Conversational Intake): adds `ticket_version`, this service's
own tracked copy of the owning ticket's real optimistic-concurrency version — seeded
from CreateTicketResponse.version at conversation-creation time (SPEC-ARO-038) and
advanced after this service's own ticket-mutating calls succeed (the real triage
call) — and `ticket_display_id`, the owning ticket's real displayId captured at the
same moment, since the real triage response carries no displayId of its own for an
escalation response to reuse later. See WorkflowInstanceRecord.ticket_version/
ticket_display_id's own docstrings for the full rule. Folded into one migration since
neither column had been applied anywhere before this revision was written.

Revision ID: 9d3b6c2e5a71
Revises: e7c1a9b04d3f
Create Date: 2026-09-01 00:10:00.000000

"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "9d3b6c2e5a71"
down_revision: str | None = "e7c1a9b04d3f"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.add_column(
        "workflow_instances",
        sa.Column("ticket_version", sa.Integer(), nullable=False, server_default="0"),
        schema=SCHEMA,
    )
    op.add_column(
        "workflow_instances",
        sa.Column("ticket_display_id", sa.String(length=50), nullable=True),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_column("workflow_instances", "ticket_display_id", schema=SCHEMA)
    op.drop_column("workflow_instances", "ticket_version", schema=SCHEMA)
