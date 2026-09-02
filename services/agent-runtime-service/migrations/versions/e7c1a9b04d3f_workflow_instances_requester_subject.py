"""workflow instances requester subject

SPEC-ARO-042 (phase-10 Conversational Intake) api-contract: "if workflow_instances has
no existing 'created-by subject' field to query against, one may need to be added" —
confirmed against the real schema that it did not exist before this spec. Adds a
nullable `requester_subject` column plus a plain btree index backing
find_most_recent_by_requester_and_workflow_type(), mirroring
0746d71d59e8_workflow_instances_ticket_id_index's own "one column, one supporting
index" shape. NULL for every pre-existing workflow_type (started from a consumed
ticket.created event, never from a directly-identified human requester) — only
StartConversationService (SPEC-ARO-038) populates it going forward.

Revision ID: e7c1a9b04d3f
Revises: f1a2b3c4d5e6
Create Date: 2026-09-01 00:00:00.000000

"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "e7c1a9b04d3f"
down_revision: str | None = "f1a2b3c4d5e6"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.add_column(
        "workflow_instances",
        sa.Column("requester_subject", sa.String(length=200), nullable=True),
        schema=SCHEMA,
    )
    op.create_index(
        "ix_workflow_instances_requester_subject", "workflow_instances", ["requester_subject"], unique=False, schema=SCHEMA
    )


def downgrade() -> None:
    op.drop_index("ix_workflow_instances_requester_subject", table_name="workflow_instances", schema=SCHEMA)
    op.drop_column("workflow_instances", "requester_subject", schema=SCHEMA)
