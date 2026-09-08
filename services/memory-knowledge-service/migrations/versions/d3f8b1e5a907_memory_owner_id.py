"""memory.memories.owner_id — per-user RAG isolation

frontend-product-vision's flagged gap: Memory/KnowledgeDocument had no owner
dimension, so the whole long-term memory pipeline was organization-wide. This
adds a nullable owner_id to memory.memories — NULL keeps a Memory org-wide
(every existing row), a value scopes retrieval to that principal
(SearchMemoryService filters `owner_id IS NULL OR owner_id = :requester_id`).
KnowledgeDocuments stay org-wide (they are ingested reference material, not
per-user), so only this one table changes. Fully backward compatible: no
existing row or code path changes behaviour while owner_id is NULL.

Revision ID: d3f8b1e5a907
Revises: c7b3f1a9d24e
Create Date: 2026-09-08
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "d3f8b1e5a907"
down_revision: str | None = "c7b3f1a9d24e"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "memory"


def upgrade() -> None:
    op.add_column("memories", sa.Column("owner_id", sa.String(200), nullable=True), schema=SCHEMA)
    op.create_index("ix_memories_owner_id", "memories", ["owner_id"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_index("ix_memories_owner_id", table_name="memories", schema=SCHEMA)
    op.drop_column("memories", "owner_id", schema=SCHEMA)
