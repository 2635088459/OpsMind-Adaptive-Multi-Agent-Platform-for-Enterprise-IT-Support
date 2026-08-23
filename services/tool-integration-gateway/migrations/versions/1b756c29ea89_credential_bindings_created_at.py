"""credential_bindings created_at

SPEC-TG-012: adds ``credential_bindings.created_at``, not itself in 07-data-
model's own column list (credential_binding_id, connector_id, tenant_id,
scope, vault_ref, rotation_version, status, last_used_at) — see
``adapters.db.models.CredentialBindingRow``'s own docstring. Safe as a
``NOT NULL`` column with no backfill: this table has had zero writers since
SPEC-TG-002 created it (SPEC-TG-012 is the first spec to write to it at all).

Revision ID: 1b756c29ea89
Revises: 690b98c93f9e
Create Date: 2026-08-18
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "1b756c29ea89"
down_revision: str | None = "690b98c93f9e"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "tool"


def upgrade() -> None:
    op.add_column("credential_bindings", sa.Column("created_at", sa.DateTime(timezone=True), nullable=False), schema=SCHEMA)


def downgrade() -> None:
    op.drop_column("credential_bindings", "created_at", schema=SCHEMA)
