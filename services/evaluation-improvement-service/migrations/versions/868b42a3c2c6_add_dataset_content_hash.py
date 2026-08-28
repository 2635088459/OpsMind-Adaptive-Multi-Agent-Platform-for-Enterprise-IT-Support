"""add dataset content_hash column

SPEC-EI-007 / 07-data-model §"Artifact 引用": a dataset-level `content_hash` (SHA-256
over every one of its own test cases' `input_hash`, frozen at publish() time) — see
infrastructure.persistence.postgres.models.EvaluationDatasetRow's own docstring.

Revision ID: 868b42a3c2c6
Revises: 33ef5244b085
Create Date: 2026-08-26
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "868b42a3c2c6"
down_revision: str | None = "33ef5244b085"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.add_column("evaluation_datasets", sa.Column("content_hash", sa.String(64), nullable=True), schema=SCHEMA)


def downgrade() -> None:
    op.drop_column("evaluation_datasets", "content_hash", schema=SCHEMA)
