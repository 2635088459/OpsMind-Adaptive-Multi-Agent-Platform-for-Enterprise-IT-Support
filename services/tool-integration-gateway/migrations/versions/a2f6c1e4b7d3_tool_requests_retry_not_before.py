"""tool_requests retry_not_before

SPEC-TG-016: adds ``tool_requests.retry_not_before`` — a real backoff-
scheduling field 07-data-model's own column list never named (it predates
retry *scheduling*; see ``domain.tool_request.ToolRequest.retry_not_before``'s
own docstring). Nullable, no backfill needed: every existing row is either
not QUEUED at all, or QUEUED from a path other than a retry (``NULL`` means
"immediately claimable", the correct default for pre-existing rows).

Revision ID: a2f6c1e4b7d3
Revises: 1b756c29ea89
Create Date: 2026-08-18
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "a2f6c1e4b7d3"
down_revision: str | None = "1b756c29ea89"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "tool"


def upgrade() -> None:
    op.add_column("tool_requests", sa.Column("retry_not_before", sa.DateTime(timezone=True), nullable=True), schema=SCHEMA)


def downgrade() -> None:
    op.drop_column("tool_requests", "retry_not_before", schema=SCHEMA)
