"""add dataset tenant_id column

SPEC-EI-008 / 11-security §"身份与权限": "07 依赖 01 提供 actor、service identity、tenant
scope 和 role claims" — a caller-asserted tenant scope on `evaluation_datasets`, plus
an index since list_published()/find_versions() both filter by it. See
infrastructure.persistence.postgres.models.EvaluationDatasetRow's own docstring.

Revision ID: a1c9f3d7e2b4
Revises: 868b42a3c2c6
Create Date: 2026-08-26
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "a1c9f3d7e2b4"
down_revision: str | None = "868b42a3c2c6"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.add_column(
        "evaluation_datasets",
        sa.Column("tenant_id", sa.String(100), nullable=False, server_default="default"),
        schema=SCHEMA,
    )
    op.create_index(
        "ix_evaluation_datasets_tenant_id", "evaluation_datasets", ["tenant_id"], schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_index("ix_evaluation_datasets_tenant_id", table_name="evaluation_datasets", schema=SCHEMA)
    op.drop_column("evaluation_datasets", "tenant_id", schema=SCHEMA)
