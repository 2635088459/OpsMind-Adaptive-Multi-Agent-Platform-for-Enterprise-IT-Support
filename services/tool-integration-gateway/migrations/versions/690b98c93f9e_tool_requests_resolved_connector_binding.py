"""tool_requests resolved connector binding

SPEC-TG-006 / 02-business-invariants INV-TG-008: "Every connector input/output
schema must be versioned. Tool Request records the schema version used at
submission time so historical requests remain interpretable after connector
upgrades." Adds the two columns ``ToolRequest.bind_connector()`` populates at
intake and ``execute_tool_request`` reuses verbatim rather than re-resolving by
capability name at execution time.

Revision ID: 690b98c93f9e
Revises: 33c0a5358f30
Create Date: 2026-08-17
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "690b98c93f9e"
down_revision: str | None = "33c0a5358f30"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "tool"


def upgrade() -> None:
    op.add_column("tool_requests", sa.Column("resolved_connector_id", postgresql.UUID(as_uuid=True), nullable=True), schema=SCHEMA)
    op.add_column("tool_requests", sa.Column("resolved_connector_version", sa.String(40), nullable=True), schema=SCHEMA)


def downgrade() -> None:
    op.drop_column("tool_requests", "resolved_connector_version", schema=SCHEMA)
    op.drop_column("tool_requests", "resolved_connector_id", schema=SCHEMA)
