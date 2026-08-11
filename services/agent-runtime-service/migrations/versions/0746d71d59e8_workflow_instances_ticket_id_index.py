"""workflow instances ticket id index

SPEC-ARO-006 (05-api-contracts "GET /workflows/by-ticket/{ticketId}"): adds a plain
btree index on workflow_instances.ticket_id so PostgresWorkflowInstanceRepository.
find_by_ticket_id() doesn't scan the whole table. Every existing ticket_id lookup before
this spec went through find_active_by_ticket_cycle_and_type, which is already covered by
the ticket_id + ticket_cycle_id + workflow_type partial unique index — that index's
leftmost column (ticket_id) would technically also serve this new query, but a dedicated
single-column index is more selective for "every instance for this ticket" and doesn't
depend on that index's WHERE-clause (non-terminal-only) restriction.

Hand-written, not the raw `alembic revision --autogenerate` output: autogenerate also
proposed dropping and recreating `uq_workflow_instances_active` (the partial unique
index from the first migration, which the SQLAlchemy model deliberately does not
declare — see WorkflowInstanceRow's own comment) and re-ordering
`ix_checkpoints_workflow_instance_created_desc` from DESC to ASC to match the model's
un-ordered Index() declaration. Both are pre-existing false diffs between the hand-authored
DDL and the declarative model, not real changes this migration should make.

Revision ID: 0746d71d59e8
Revises: f958dd5078b1
Create Date: 2026-08-10 21:47:57.845700

"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "0746d71d59e8"
down_revision: str | None = "f958dd5078b1"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.create_index("ix_workflow_instances_ticket_id", "workflow_instances", ["ticket_id"], unique=False, schema=SCHEMA)


def downgrade() -> None:
    op.drop_index("ix_workflow_instances_ticket_id", table_name="workflow_instances", schema=SCHEMA)
