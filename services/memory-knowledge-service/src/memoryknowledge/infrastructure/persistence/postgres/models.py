"""SPEC-MK-002 / 07-data-model: SQLAlchemy ORM models for the `memory` PostgreSQL
schema. This module is the entire ORM boundary — application and domain code never
see these classes (enforced by import-linter's "Application must not depend on
infrastructure" contract); infrastructure.persistence.postgres.repositories
translates between these rows and the application-layer domain objects/records.

Some columns exist here because 07-data-model lists them, but no SPEC-MK-001/002
application service populates them yet — those stay NULL/defaulted until the spec
that owns the behavior lands (noted per-column below), mirroring
agent-runtime-service's own SPEC-ARO-002 models.py precedent exactly.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Integer, MetaData, Numeric, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

SCHEMA = "memory"


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA)


class WorkingMemoryRow(Base):
    __tablename__ = "working_memory"
    __table_args__ = (
        # 02-business-invariants: "同一个 scope 只能有一个 active WorkingMemory" — a plain
        # UNIQUE constraint can't express "only while ACTIVE" (ARCHIVED/DELETED rows for
        # the same scope must remain), so this is created by the migration itself as a
        # partial unique index, mirroring agent-runtime-service's own
        # uq_workflow_instances_active precedent exactly.
        Index("ix_working_memory_ticket_id", "ticket_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    ticket_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    ticket_cycle_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    workflow_instance_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    facts_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    hypotheses_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    rejected_hypotheses_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    completed_tasks_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    pending_tasks_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    tool_evidence_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    approval_decision_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    # 07-data-model calls this `summary`; named context_summary here to match
    # domain.working_memory.WorkingMemory.context_summary exactly.
    context_summary: Mapped[str] = mapped_column(Text, nullable=False, default="")
    # Beyond 07-data-model's minimum column list, but a real domain field (WorkingMemory.
    # updated_by) — kept as its own column rather than folded into body_json.
    updated_by: Mapped[str] = mapped_column(String(200), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class MemoryRow(Base):
    __tablename__ = "memories"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    memory_type: Mapped[str] = mapped_column(String(40), nullable=False)
    # domain.memory.Memory carries no status of its own (only MemoryVersion does) — this
    # column tracks "this Memory identity is active" (as opposed to a fully deleted
    # identity), defaulting to ACTIVE at creation; no current service transitions it.
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="ACTIVE")
    # No FK: would be circular with memory_versions.memory_id -> memories.id. Written by
    # PostgresMemoryRepository.save_version() as internal bookkeeping whenever it saves
    # an ACTIVE version — a denormalized pointer, not a new business rule (the owning
    # MemoryVersion row is the source of truth; find_active_version() still queries
    # memory_versions.status directly, never this column).
    current_version_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    # 07-data-model columns; application_code/category still have no populating use
    # case (no command carries them yet). classification is real as of SPEC-MK-025 —
    # PublishMemoryService now threads a caller-supplied value through domain.memory.
    # Memory.classification; "INTERNAL" remains the honest default for a publish
    # request that doesn't specify one.
    application_code: Mapped[str | None] = mapped_column(String(100), nullable=True)
    category: Mapped[str | None] = mapped_column(String(100), nullable=True)
    classification: Mapped[str] = mapped_column(String(40), nullable=False, default="INTERNAL")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class MemoryVersionRow(Base):
    __tablename__ = "memory_versions"
    __table_args__ = (
        UniqueConstraint("memory_id", "version", name="uq_memory_versions_memory_id_version"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    memory_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.memories.id"), nullable=False)
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    source_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False)
    redaction_report_json: Mapped[dict] = mapped_column(JSONB, nullable=False)
    confidence_score: Mapped[float] = mapped_column(Numeric, nullable=False)
    source_trust_score: Mapped[float] = mapped_column(Numeric, nullable=False)
    embedding_ref_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    source_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    supersedes_version_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.memory_versions.id"), nullable=True
    )
    created_by: Mapped[str] = mapped_column(String(200), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class MemoryCandidateRow(Base):
    __tablename__ = "memory_candidates"
    __table_args__ = (
        # 06-event-contracts: "candidate extraction 还必须使用 sourceHash + memoryType 防止
        # 不同 eventId 重复创建候选" — SPEC-MK-010/011 made ExtractMemoryCandidateService
        # compute and upsert on source_hash, so this constraint is now load-bearing (was
        # a documented no-op through SPEC-MK-001/002, since nothing populated the column).
        UniqueConstraint("source_hash", "memory_type", name="uq_memory_candidates_source_hash_memory_type"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    memory_type: Mapped[str] = mapped_column(String(40), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    source_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    source_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False)
    candidate_text: Mapped[str] = mapped_column(Text, nullable=False)
    redacted_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    redaction_report_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    confidence_score: Mapped[float | None] = mapped_column(Numeric, nullable=True)
    usefulness_score: Mapped[float | None] = mapped_column(Numeric, nullable=True)
    duplicate_of_memory_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.memories.id"), nullable=True
    )
    conflict_set_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    review_required: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Beyond 07-data-model's minimum list, but a real domain field
    # (MemoryCandidate.rejection_reason).
    rejection_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class KnowledgeDocumentRow(Base):
    __tablename__ = "knowledge_documents"
    __table_args__ = (
        UniqueConstraint("source_system", "external_id", "version", name="uq_knowledge_documents_natural_key"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    source_system: Mapped[str] = mapped_column(String(100), nullable=False)
    external_id: Mapped[str] = mapped_column(String(200), nullable=False)
    # 07-data-model types this `text`; kept as int here to match
    # domain.knowledge_document.KnowledgeDocument.version's own int type exactly (a
    # monotonic per-document-family counter, not a free-form label).
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str] = mapped_column(String(500), nullable=False)
    document_type: Mapped[str] = mapped_column(String(100), nullable=False)
    # classification is real as of SPEC-MK-025 — IngestKnowledgeDocumentService now
    # threads a caller-supplied value through domain.knowledge_document.
    # KnowledgeDocument.classification; "INTERNAL" remains the honest default.
    classification: Mapped[str] = mapped_column(String(40), nullable=False, default="INTERNAL")
    acl_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    content_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    # 07-data-model column; infrastructure.document_parser stores chunk content
    # directly (no external blob store exists yet), so this stays unpopulated.
    raw_content_ref: Mapped[str | None] = mapped_column(Text, nullable=True)
    effective_from: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class DocumentChunkRow(Base):
    __tablename__ = "document_chunks"
    __table_args__ = (
        UniqueConstraint("document_id", "chunk_index", name="uq_document_chunks_document_id_chunk_index"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    document_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.knowledge_documents.id"), nullable=False)
    # Beyond 07-data-model's minimum list, but a real domain field
    # (DocumentChunk.document_version) — 02-business-invariants: "Document chunk 必须可
    # 追溯到 document version."
    document_version: Mapped[int] = mapped_column(Integer, nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    content_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    heading_path: Mapped[str | None] = mapped_column(String(500), nullable=True)
    token_count: Mapped[int] = mapped_column(Integer, nullable=False)
    # domain.knowledge_document.DocumentChunk carries no status of its own yet — this
    # column defaults to ACTIVE (visible), consistent with MemoryRow.status's own
    # "no current service transitions it" note.
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="ACTIVE")
    embedding_ref_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)


class EmbeddingRow(Base):
    """07-data-model §"memory.embeddings". Primary-keyed by vector_id (not a separate
    surrogate id) to match domain.values.EmbeddingRef.vector_id — the same value
    EmbeddingRepository.find(vector_id) is keyed by.
    """

    __tablename__ = "embeddings"

    vector_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    # 07-data-model columns; EmbeddingRepository.save(embedding_ref, vector) carries no
    # owner/content-hash today (infrastructure.embedding.embedding_provider computes a
    # vector from already-redacted text without threading an owner reference through) —
    # phase-05 (retrieval-and-knowledge-graph) is the real owner of wiring these.
    owner_type: Mapped[str | None] = mapped_column(String(40), nullable=True)
    owner_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    content_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    provider: Mapped[str] = mapped_column(String(100), nullable=False)
    model: Mapped[str] = mapped_column(String(200), nullable=False)
    dimensions: Mapped[int] = mapped_column(Integer, nullable=False)
    # pgvector-ready storage (SPEC-MK-002 domain-rules) — no fixed dimension pinned at
    # the column level since infrastructure.embedding.embedding_provider's own
    # placeholder and any future real provider may not share one; an ivfflat/hnsw ANN
    # index (07-data-model: "按 MVP 数据量选择") is deferred until real query volume
    # justifies one.
    embedding: Mapped[list[float]] = mapped_column(Vector(), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class GraphNodeRow(Base):
    __tablename__ = "graph_nodes"
    __table_args__ = (
        UniqueConstraint("node_type", "stable_key", name="uq_graph_nodes_node_type_stable_key"),
        Index("ix_graph_nodes_type_status", "node_type", "status"),
        Index("ix_graph_nodes_classification_status", "classification", "status"),
        Index("ix_graph_nodes_properties_gin", "properties_json", postgresql_using="gin"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    node_type: Mapped[str] = mapped_column(String(60), nullable=False)
    stable_key: Mapped[str] = mapped_column(String(300), nullable=False)
    display_name: Mapped[str] = mapped_column(String(500), nullable=False)
    properties_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    classification: Mapped[str] = mapped_column(String(40), nullable=False)
    source_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class GraphEdgeRow(Base):
    __tablename__ = "graph_edges"
    __table_args__ = (
        UniqueConstraint("from_node_id", "to_node_id", "edge_type", "source_hash", name="uq_graph_edges_natural_key"),
        Index("ix_graph_edges_from_type_status", "from_node_id", "edge_type", "status"),
        Index("ix_graph_edges_to_type_status", "to_node_id", "edge_type", "status"),
        Index("ix_graph_edges_type_status", "edge_type", "status"),
        Index("ix_graph_edges_confidence", "confidence"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    edge_type: Mapped[str] = mapped_column(String(60), nullable=False)
    from_node_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.graph_nodes.id"), nullable=False)
    to_node_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.graph_nodes.id"), nullable=False)
    confidence: Mapped[float] = mapped_column(Numeric, nullable=False)
    evidence_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False)
    source_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    properties_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class RetrievalLogRow(Base):
    __tablename__ = "retrieval_logs"
    __table_args__ = (Index("ix_retrieval_logs_created_at", "created_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    requester_type: Mapped[str] = mapped_column(String(40), nullable=False)
    requester_id: Mapped[str] = mapped_column(String(200), nullable=False)
    ticket_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    # Beyond 07-data-model's minimum list, but a real domain field (RetrievalLog.
    # ticket_cycle_id).
    ticket_cycle_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    workflow_instance_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    query_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    filters_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    result_refs_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    graph_paths_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    degraded: Mapped[bool] = mapped_column(Boolean, nullable=False)
    latency_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ProcessedEventRow(Base):
    """SPEC-MK-001 domain-rules: "所有消费事件必须 processed-event 去重." No consumer wired
    yet (phase-03 is the first) — schema/adapter only, mirroring
    agent-runtime-service's own SPEC-ARO-002 precedent for this exact table.
    """

    __tablename__ = "processed_events"

    event_id: Mapped[str] = mapped_column(String(200), primary_key=True)
    consumer_name: Mapped[str] = mapped_column(String(100), primary_key=True)
    event_type: Mapped[str | None] = mapped_column(String(200), nullable=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class OutboxEventRow(Base):
    __tablename__ = "outbox_events"
    __table_args__ = (Index("ix_outbox_events_status_available_at", "status", "available_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    event_type: Mapped[str] = mapped_column(String(200), nullable=False)
    schema_version: Mapped[int] = mapped_column(Integer, nullable=False)
    aggregate_id: Mapped[str] = mapped_column(String(200), nullable=False)
    ticket_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    correlation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    causation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    # 08-transaction-and-outbox (deferred detail to SPEC-MK-003) §"Outbox Publisher":
    # DispatchOutboxEventsService owns the PENDING -> PUBLISHED / DEAD_LETTER
    # transitions; real broker wiring (RabbitMQ) is SPEC-MK-003.
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    available_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CommandIdempotencyRow(Base):
    """07-data-model §"command_idempotency" / SPEC-MK-003 09-concurrency-and-
    idempotency §"Command Idempotency": every extract/publish/deprecate/delete command
    with an IdempotencyKey goes through
    memoryknowledge.application.services.idempotency.CommandIdempotencyGuard, backed
    by this table. request_hash lets the guard detect "same key, different payload"
    (IdempotencyKeyReusedException) instead of silently replaying a response that
    doesn't match what the caller just asked for.
    """

    __tablename__ = "command_idempotency"

    idempotency_key: Mapped[str] = mapped_column(String(200), primary_key=True)
    command_type: Mapped[str] = mapped_column(String(100), nullable=False)
    target_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    response_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class AuditEventRow(Base):
    """SPEC-MK-003 12-observability §"Audit Events": "审计事件必须可长期保存." Append-only —
    no mark_*/update method, mirroring OutboxEventRow's insert-then-status-transitions
    shape being absent here: an audit row's own fields never change after being
    written. `id` is its own surrogate primary key (no natural composite key: many
    audit rows can legitimately share the same resource_id/action).
    """

    __tablename__ = "audit_events"
    __table_args__ = (
        Index("ix_audit_events_resource_type_resource_id", "resource_type", "resource_id"),
        Index("ix_audit_events_occurred_at", "occurred_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    audit_type: Mapped[str] = mapped_column(String(40), nullable=False)
    action: Mapped[str] = mapped_column(String(100), nullable=False)
    resource_type: Mapped[str] = mapped_column(String(40), nullable=False)
    resource_id: Mapped[str] = mapped_column(String(100), nullable=False)
    ticket_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    actor_type: Mapped[str] = mapped_column(String(40), nullable=False)
    actor_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    outcome: Mapped[str] = mapped_column(String(20), nullable=False)
    correlation_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    causation_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    detail: Mapped[str] = mapped_column(Text, nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class PoisonEventRow(Base):
    """SPEC-MK-029 10-failure-handling §"Poison Event": "写入 poison event 表" — a
    separate table from processed_events/outbox_events, since a poisoned delivery is
    neither "already applied" nor "waiting to be published"; it is parked for manual
    investigation and possible replay (05-api-contracts §"Admin API": "mark poison
    event quarantined"). `id` is its own surrogate primary key, mirroring
    AuditEventRow's own reasoning: many poison rows could in principle share the same
    event_id across retried deliveries under different consumer_names.
    """

    __tablename__ = "poison_events"
    __table_args__ = (Index("ix_poison_events_recorded_at", "recorded_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    event_id: Mapped[str] = mapped_column(String(200), nullable=False)
    consumer_name: Mapped[str] = mapped_column(String(100), nullable=False)
    event_type: Mapped[str] = mapped_column(String(200), nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    error_message: Mapped[str] = mapped_column(Text, nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    quarantined_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
