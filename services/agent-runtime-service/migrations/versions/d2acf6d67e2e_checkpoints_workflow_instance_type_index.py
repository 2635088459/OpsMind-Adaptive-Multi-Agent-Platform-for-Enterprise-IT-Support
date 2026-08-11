"""checkpoints workflow instance type index

SPEC-ARO-008 (04-use-cases UC-02 step 6): adds a btree index on
checkpoints.(workflow_instance_id, checkpoint_type) so
PostgresCheckpointRepository.find_latest_by_workflow_instance_id_and_type() — called on
every Agent Task completion to re-derive the task graph from the STARTED checkpoint —
doesn't scan every checkpoint ever recorded for the instance.

Hand-written, not the raw `alembic revision --autogenerate` output — same two pre-existing
false diffs as migration 0746d71d59e8 (dropping the hand-authored
uq_workflow_instances_active partial unique index, and re-ordering
ix_checkpoints_workflow_instance_created_desc from DESC to ASC to match the model's
un-ordered Index() declaration), stripped from the migration body.

Revision ID: d2acf6d67e2e
Revises: 0746d71d59e8
Create Date: 2026-08-11 18:10:00.000000

"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "d2acf6d67e2e"
down_revision: str | None = "0746d71d59e8"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "agent_runtime"


def upgrade() -> None:
    op.create_index(
        "ix_checkpoints_workflow_instance_type", "checkpoints", ["workflow_instance_id", "checkpoint_type"], unique=False, schema=SCHEMA
    )


def downgrade() -> None:
    op.drop_index("ix_checkpoints_workflow_instance_type", table_name="checkpoints", schema=SCHEMA)
