"""create outbox, processed event, command idempotency, and audit tables

SPEC-EI-003 / 08-transaction-and-outbox, 09-concurrency-and-idempotency,
12-observability: `evaluation_outbox_events`, `evaluation_processed_events`, and
`evaluation_audit_records` (07-data-model's own literal names) plus
`evaluation_command_idempotency` (a pragmatic extension — see
infrastructure.persistence.postgres.models's own docstring). Real RabbitMQ wiring for
EventPublisherPort stays deferred past this spec — see infrastructure.event_publisher's
own module docstring for why — this migration only ever concerns the durable
persistence side.

Revision ID: 33ef5244b085
Revises: 06670df0f457
Create Date: 2026-08-26
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "33ef5244b085"
down_revision: str | None = "06670df0f457"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.create_table(
        "evaluation_outbox_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_type", sa.String(100), nullable=False),
        sa.Column("schema_version", sa.Integer, nullable=False, server_default="1"),
        sa.Column("aggregate_id", sa.String(200), nullable=False),
        sa.Column("payload", sa.Text, nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("correlation_id", sa.String(200), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )
    # DispatchOutboxEventsService's own scan predicate:
    # `status IN ('PENDING','FAILED') AND (available_at IS NULL OR available_at <= now())`.
    op.create_index("ix_evaluation_outbox_events_status_available_at", "evaluation_outbox_events", ["status", "available_at"], schema=SCHEMA)

    op.create_table(
        "evaluation_processed_events",
        sa.Column("event_id", sa.String(200), primary_key=True),
        sa.Column("consumer_name", sa.String(200), primary_key=True),
        sa.Column("event_type", sa.String(100), nullable=True),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )

    op.create_table(
        "evaluation_command_idempotency",
        sa.Column("idempotency_key", sa.String(300), primary_key=True),
        sa.Column("command_type", sa.String(100), nullable=False),
        sa.Column("target_id", sa.String(200), nullable=True),
        sa.Column("request_hash", sa.String(128), nullable=False),
        sa.Column("response_json", sa.Text, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )

    op.create_table(
        "evaluation_audit_records",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("action", sa.String(100), nullable=False),
        sa.Column("resource_type", sa.String(100), nullable=False),
        sa.Column("resource_id", sa.String(200), nullable=False),
        sa.Column("actor", sa.String(200), nullable=False),
        sa.Column("outcome", sa.String(20), nullable=False),
        sa.Column("correlation_id", sa.String(200), nullable=True),
        sa.Column("detail", sa.Text, nullable=False, server_default="{}"),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_evaluation_audit_records_occurred_at", "evaluation_audit_records", ["occurred_at"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_table("evaluation_audit_records", schema=SCHEMA)
    op.drop_table("evaluation_command_idempotency", schema=SCHEMA)
    op.drop_table("evaluation_processed_events", schema=SCHEMA)
    op.drop_table("evaluation_outbox_events", schema=SCHEMA)
