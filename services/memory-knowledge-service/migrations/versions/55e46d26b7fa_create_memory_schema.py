"""create memory schema

SPEC-MK-002 / 07-data-model: dedicated `memory` schema plus its thirteen tables
(memories, memory_versions, memory_candidates, working_memory, knowledge_documents,
document_chunks, embeddings, graph_nodes, graph_edges, retrieval_logs,
processed_events, outbox_events, command_idempotency). Mirrors the sibling
ticket-workflow-service's V001__create_ticket_schema.sql pattern and
agent-runtime-service's own SPEC-ARO-002 migration: one shared Postgres database,
one schema per service, never mixed. Also enables the pgvector extension
(SPEC-MK-002 domain-rules: "pgvector-ready embedding storage").

Revision ID: 55e46d26b7fa
Revises:
Create Date: 2026-08-15
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "55e46d26b7fa"
down_revision: str | None = None
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "memory"


def upgrade() -> None:
    op.execute(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")
    # SPEC-MK-002 domain-rules: "pgvector-ready embedding storage" — extensions are
    # database-instance-wide (not per-schema); safe to call unconditionally.
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    op.create_table(
        "memories",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("memory_type", sa.String(40), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="ACTIVE"),
        sa.Column("current_version_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("application_code", sa.String(100), nullable=True),
        sa.Column("category", sa.String(100), nullable=True),
        sa.Column("classification", sa.String(40), nullable=False, server_default="INTERNAL"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )

    op.create_table(
        "memory_versions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("memory_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.memories.id"), nullable=False),
        sa.Column("version", sa.Integer, nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("summary", sa.Text, nullable=True),
        sa.Column("source_refs_json", postgresql.JSONB, nullable=False),
        sa.Column("redaction_report_json", postgresql.JSONB, nullable=False),
        sa.Column("confidence_score", sa.Numeric, nullable=False),
        sa.Column("source_trust_score", sa.Numeric, nullable=False),
        sa.Column("embedding_ref_json", postgresql.JSONB, nullable=True),
        sa.Column("source_hash", sa.String(128), nullable=False),
        sa.Column("supersedes_version_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("created_by", sa.String(200), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("memory_id", "version", name="uq_memory_versions_memory_id_version"),
        schema=SCHEMA,
    )
    op.create_foreign_key(
        "fk_memory_versions_supersedes_version_id", "memory_versions", "memory_versions",
        ["supersedes_version_id"], ["id"], source_schema=SCHEMA, referent_schema=SCHEMA,
    )
    # 02-business-invariants: "同一个 memoryId 同时只能有一个 ACTIVE version" — a partial
    # unique index, mirroring workflow_instances' own uq_workflow_instances_active
    # precedent (a plain UNIQUE constraint can't express "only while ACTIVE").
    op.execute(
        f"""
        CREATE UNIQUE INDEX uq_memory_versions_one_active_per_memory
        ON {SCHEMA}.memory_versions (memory_id)
        WHERE status = 'ACTIVE'
        """
    )

    op.create_table(
        "memory_candidates",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("memory_type", sa.String(40), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("source_hash", sa.String(128), nullable=True),
        sa.Column("source_refs_json", postgresql.JSONB, nullable=False),
        sa.Column("candidate_text", sa.Text, nullable=False),
        sa.Column("redacted_text", sa.Text, nullable=True),
        sa.Column("redaction_report_json", postgresql.JSONB, nullable=True),
        sa.Column("confidence_score", sa.Numeric, nullable=True),
        sa.Column("usefulness_score", sa.Numeric, nullable=True),
        sa.Column("duplicate_of_memory_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.memories.id"), nullable=True),
        sa.Column("conflict_set_id", sa.String(100), nullable=True),
        sa.Column("review_required", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("rejection_reason", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("source_hash", "memory_type", name="uq_memory_candidates_source_hash_memory_type"),
        schema=SCHEMA,
    )

    op.create_table(
        "working_memory",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("ticket_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("ticket_cycle_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("workflow_instance_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("version", sa.Integer, nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("facts_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("hypotheses_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("rejected_hypotheses_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("completed_tasks_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("pending_tasks_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("tool_evidence_refs_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("approval_decision_refs_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("context_summary", sa.Text, nullable=False, server_default=""),
        sa.Column("updated_by", sa.String(200), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_working_memory_ticket_id", "working_memory", ["ticket_id"], schema=SCHEMA)
    # 02-business-invariants: "同一个 scope 只能有一个 active WorkingMemory".
    op.execute(
        f"""
        CREATE UNIQUE INDEX uq_working_memory_active_scope
        ON {SCHEMA}.working_memory (ticket_id, ticket_cycle_id, workflow_instance_id)
        WHERE status = 'ACTIVE'
        """
    )

    op.create_table(
        "knowledge_documents",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("source_system", sa.String(100), nullable=False),
        sa.Column("external_id", sa.String(200), nullable=False),
        sa.Column("version", sa.Integer, nullable=False),
        sa.Column("title", sa.String(500), nullable=False),
        sa.Column("document_type", sa.String(100), nullable=False),
        sa.Column("classification", sa.String(40), nullable=False, server_default="INTERNAL"),
        sa.Column("acl_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("content_hash", sa.String(128), nullable=False),
        sa.Column("raw_content_ref", sa.Text, nullable=True),
        sa.Column("effective_from", sa.DateTime(timezone=True), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("failure_reason", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("source_system", "external_id", "version", name="uq_knowledge_documents_natural_key"),
        schema=SCHEMA,
    )

    op.create_table(
        "document_chunks",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("document_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.knowledge_documents.id"), nullable=False),
        sa.Column("document_version", sa.Integer, nullable=False),
        sa.Column("chunk_index", sa.Integer, nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("content_hash", sa.String(128), nullable=False),
        sa.Column("heading_path", sa.String(500), nullable=True),
        sa.Column("token_count", sa.Integer, nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="ACTIVE"),
        sa.Column("embedding_ref_json", postgresql.JSONB, nullable=True),
        sa.UniqueConstraint("document_id", "chunk_index", name="uq_document_chunks_document_id_chunk_index"),
        schema=SCHEMA,
    )

    op.create_table(
        "embeddings",
        sa.Column("vector_id", sa.String(64), primary_key=True),
        sa.Column("owner_type", sa.String(40), nullable=True),
        sa.Column("owner_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("content_hash", sa.String(128), nullable=True),
        sa.Column("provider", sa.String(100), nullable=False),
        sa.Column("model", sa.String(200), nullable=False),
        sa.Column("dimensions", sa.Integer, nullable=False),
        sa.Column("embedding", Vector(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )

    op.create_table(
        "graph_nodes",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("node_type", sa.String(60), nullable=False),
        sa.Column("stable_key", sa.String(300), nullable=False),
        sa.Column("display_name", sa.String(500), nullable=False),
        sa.Column("properties_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("classification", sa.String(40), nullable=False),
        sa.Column("source_refs_json", postgresql.JSONB, nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("node_type", "stable_key", name="uq_graph_nodes_node_type_stable_key"),
        schema=SCHEMA,
    )
    op.create_index("ix_graph_nodes_type_status", "graph_nodes", ["node_type", "status"], schema=SCHEMA)
    op.create_index("ix_graph_nodes_classification_status", "graph_nodes", ["classification", "status"], schema=SCHEMA)
    op.create_index(
        "ix_graph_nodes_properties_gin", "graph_nodes", ["properties_json"], schema=SCHEMA, postgresql_using="gin"
    )

    op.create_table(
        "graph_edges",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("edge_type", sa.String(60), nullable=False),
        sa.Column("from_node_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.graph_nodes.id"), nullable=False),
        sa.Column("to_node_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.graph_nodes.id"), nullable=False),
        sa.Column("confidence", sa.Numeric, nullable=False),
        sa.Column("evidence_refs_json", postgresql.JSONB, nullable=False),
        sa.Column("source_hash", sa.String(128), nullable=False),
        sa.Column("properties_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("from_node_id", "to_node_id", "edge_type", "source_hash", name="uq_graph_edges_natural_key"),
        schema=SCHEMA,
    )
    op.create_index("ix_graph_edges_from_type_status", "graph_edges", ["from_node_id", "edge_type", "status"], schema=SCHEMA)
    op.create_index("ix_graph_edges_to_type_status", "graph_edges", ["to_node_id", "edge_type", "status"], schema=SCHEMA)
    op.create_index("ix_graph_edges_type_status", "graph_edges", ["edge_type", "status"], schema=SCHEMA)
    op.create_index("ix_graph_edges_confidence", "graph_edges", ["confidence"], schema=SCHEMA)

    op.create_table(
        "retrieval_logs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("requester_type", sa.String(40), nullable=False),
        sa.Column("requester_id", sa.String(200), nullable=False),
        sa.Column("ticket_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("ticket_cycle_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("workflow_instance_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("query_hash", sa.String(128), nullable=False),
        sa.Column("filters_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("result_refs_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("graph_paths_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("degraded", sa.Boolean, nullable=False),
        sa.Column("latency_ms", sa.Integer, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_retrieval_logs_created_at", "retrieval_logs", ["created_at"], schema=SCHEMA)

    op.create_table(
        "processed_events",
        sa.Column("event_id", sa.String(200), primary_key=True),
        sa.Column("consumer_name", sa.String(100), primary_key=True),
        sa.Column("event_type", sa.String(200), nullable=True),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )

    op.create_table(
        "outbox_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_type", sa.String(200), nullable=False),
        sa.Column("schema_version", sa.Integer, nullable=False),
        sa.Column("aggregate_id", sa.String(200), nullable=False),
        sa.Column("ticket_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("payload_json", sa.Text, nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("causation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="PENDING"),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )
    op.create_index("ix_outbox_events_status_available_at", "outbox_events", ["status", "available_at"], schema=SCHEMA)

    op.create_table(
        "command_idempotency",
        sa.Column("idempotency_key", sa.String(200), primary_key=True),
        sa.Column("command_type", sa.String(100), nullable=False),
        sa.Column("result_ref", sa.Text, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("command_idempotency", schema=SCHEMA)
    op.drop_table("outbox_events", schema=SCHEMA)
    op.drop_table("processed_events", schema=SCHEMA)
    op.drop_table("retrieval_logs", schema=SCHEMA)
    op.drop_table("graph_edges", schema=SCHEMA)
    op.drop_table("graph_nodes", schema=SCHEMA)
    op.drop_table("embeddings", schema=SCHEMA)
    op.drop_table("document_chunks", schema=SCHEMA)
    op.drop_table("knowledge_documents", schema=SCHEMA)
    op.drop_table("working_memory", schema=SCHEMA)
    op.drop_table("memory_candidates", schema=SCHEMA)
    op.execute(f"ALTER TABLE {SCHEMA}.memory_versions DROP CONSTRAINT fk_memory_versions_supersedes_version_id")
    op.drop_table("memory_versions", schema=SCHEMA)
    op.drop_table("memories", schema=SCHEMA)
    op.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
