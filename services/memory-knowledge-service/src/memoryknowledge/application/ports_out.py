"""Output ports (13-package-and-class-design §"Ports": "Output ports" — all 18 named
there, plus CommandIdempotencyRepository, mirroring agent-runtime-service's own extra
beyond its LLD list). Structural typing.Protocol — infrastructure adapters satisfy
these by shape, never by inheritance.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Protocol

from memoryknowledge.application.commands import GraphEntityInput, GraphRelationInput
from memoryknowledge.application.records import AuditRecordEntry, CommandIdempotencyRecord, OutboxRecord, TicketSnapshot, WorkflowTrace
from memoryknowledge.domain.enums import GraphNodeType, MemoryCandidateStatus, MemoryType, MemoryVersionStatus
from memoryknowledge.domain.ids import (
    GraphEdgeId,
    GraphNodeId,
    IdempotencyKey,
    KnowledgeDocumentId,
    MemoryCandidateId,
    MemoryId,
    MemoryVersionId,
    TicketCycleId,
    TicketId,
    WorkflowInstanceId,
    WorkingMemoryId,
)
from memoryknowledge.domain.knowledge_document import DocumentChunk, KnowledgeDocument
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.memory_candidate import MemoryCandidate
from memoryknowledge.domain.retrieval import RetrievalLog
from memoryknowledge.domain.values import AccessScope, EmbeddingRef, GraphPath, RedactionReport, RetrievalResultItem, SourceRef
from memoryknowledge.domain.working_memory import WorkingMemory


class ClockPort(Protocol):
    def now(self) -> datetime: ...


class WorkingMemoryRepository(Protocol):
    """02-business-invariants: "同一个 scope 只能有一个 active WorkingMemory"; "更新必须使用
    optimistic version."
    """

    def find_by_id(self, working_memory_id: WorkingMemoryId) -> WorkingMemory | None: ...

    def find_active_by_scope(
        self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_instance_id: WorkflowInstanceId
    ) -> WorkingMemory | None: ...

    def save(self, working_memory: WorkingMemory) -> WorkingMemory:
        """Inserts a brand new row (working_memory.version == 1) — raising
        WorkingMemoryScopeConflictException if an ACTIVE row already exists for the same
        scope — or replaces an existing one under optimistic-concurrency control: the
        stored row's version must equal working_memory.version - 1. Raises
        WorkingMemoryVersionConflictException if the stored version has moved on.
        """
        ...


class MemoryCandidateRepository(Protocol):
    def find_by_id(self, candidate_id: MemoryCandidateId) -> MemoryCandidate | None: ...

    def find_by_source_hash(self, source_hash: str, memory_type: MemoryType) -> MemoryCandidate | None:
        """07-data-model `memory.memory_candidates` §"唯一键": `source_hash, memory_type`."""
        ...

    def save(self, candidate: MemoryCandidate, expected_status: MemoryCandidateStatus | None) -> MemoryCandidate:
        """expected_status=None inserts a brand new candidate. Otherwise replaces an
        existing one under a compare-and-swap on its current status — raises
        MemoryCandidateStatusConflictException if the stored status no longer matches
        (mirrors WorkingMemoryRepository.save()'s version CAS, using status since
        MemoryCandidate carries no separate version field — 01-domain-model's own field
        list).
        """
        ...


class MemoryRepository(Protocol):
    """Owns both the Memory identity and its MemoryVersion history — 01-domain-model:
    "长期记忆的逻辑身份。当前有效内容由 MemoryVersion 表达."
    """

    def find_memory_by_id(self, memory_id: MemoryId) -> Memory | None: ...

    def save_memory(self, memory: Memory) -> Memory:
        """Idempotent insert — a Memory's own fields never change after creation, so a
        second save() of the same memory_id is a no-op, not a conflict.
        """
        ...

    def find_active_version(self, memory_id: MemoryId) -> MemoryVersion | None:
        """02-business-invariants: "同一个 memoryId 同时只能有一个 ACTIVE version."."""
        ...

    def find_version_by_id(self, memory_version_id: MemoryVersionId) -> MemoryVersion | None: ...

    def find_versions(self, memory_id: MemoryId) -> list[MemoryVersion]: ...

    def save_version(self, version: MemoryVersion, expected_status: MemoryVersionStatus | None) -> MemoryVersion:
        """expected_status=None inserts a brand new version. Otherwise replaces an
        existing one under a compare-and-swap on its current status.
        """
        ...

    def find_active_versions_by_type(self, memory_type_names: tuple[str, ...], limit: int) -> list[MemoryVersion]:
        """SearchMemoryService's naive in-memory scan target — a linear scan over ACTIVE
        versions is acceptable at this spec's scope (real pgvector-backed retrieval is
        phase-05, retrieval-and-knowledge-graph); this is the seam that scan will replace.
        """
        ...

    def find_by_source_hash(self, source_hash: str) -> MemoryVersion | None:
        """ValidateMemoryCandidateService's dedup check — 02-business-invariants:
        "DUPLICATE candidate 不能创建新的 Memory，只能链接到既有 Memory."
        """
        ...


class KnowledgeDocumentRepository(Protocol):
    def find_by_id(self, document_id: KnowledgeDocumentId) -> KnowledgeDocument | None: ...

    def find_by_natural_key(self, source_system: str, external_id: str, version: int) -> KnowledgeDocument | None:
        """02-business-invariants: "同一个 sourceSystem + externalId + version 只能
        ingestion 一次."
        """
        ...

    def save(self, document: KnowledgeDocument, expected_status: str | None) -> KnowledgeDocument:
        """expected_status is a DocumentIngestionStatus.name, or None to insert."""
        ...

    def save_chunks(self, chunks: tuple[DocumentChunk, ...]) -> None:
        """02-business-invariants: "CHUNKED 后 chunks 不可原地修改" — always an append of
        newly-created chunks, never an in-place update of a previously-saved chunk.
        """
        ...

    def find_chunks(self, document_id: KnowledgeDocumentId) -> list[DocumentChunk]: ...

    def find_active_chunks(self, limit: int) -> list[DocumentChunk]:
        """SearchMemoryService's naive in-memory scan target for document chunks —
        mirrors MemoryRepository.find_active_versions_by_type()'s own deferred-real-
        retrieval seam.
        """
        ...


class EmbeddingRepository(Protocol):
    """Stores the vector an EmbeddingProvider produced, keyed by its EmbeddingRef —
    distinct from EmbeddingProvider (which computes a vector from text); pgvector-backed
    storage is phase-05, this in-memory seam is what that spec replaces.
    """

    def save(self, embedding_ref: EmbeddingRef, vector: tuple[float, ...]) -> None: ...

    def find(self, vector_id: str) -> tuple[float, ...] | None: ...


class RetrievalLogRepository(Protocol):
    """02-business-invariants §"检索不变量" (implied): every retrieval, degraded or not,
    is logged — 05-api-contracts §"API 原则": "Search API 不改变 Memory 状态，只写 retrieval
    log."
    """

    def append(self, log: RetrievalLog) -> None: ...

    def find_recent(self, limit: int) -> list[RetrievalLog]:
        """Newest first — the admin visibility surface, mirroring
        agent-runtime-service's AuditRecordRepository.find_all()'s own shape.
        """
        ...


class GraphNodeRepository(Protocol):
    def find_by_id(self, node_id: GraphNodeId) -> GraphNode | None: ...

    def find_by_stable_key(self, stable_key: str, node_type: GraphNodeType) -> GraphNode | None:
        """02-business-invariants: "stableKey + nodeType 唯一，防止同一 service / symptom
        被重复建点."
        """
        ...

    def save(self, node: GraphNode) -> GraphNode:
        """Upsert keyed by node_id: a brand new node_id inserts; an existing one replaces
        (status transitions are the only mutation GraphNode allows after creation).
        """
        ...

    def find_by_ids(self, node_ids: tuple[GraphNodeId, ...]) -> list[GraphNode]: ...


class GraphEdgeRepository(Protocol):
    def find_by_id(self, edge_id: GraphEdgeId) -> GraphEdge | None: ...

    def find_by_natural_key(
        self, from_node_id: GraphNodeId, to_node_id: GraphNodeId, edge_type: str, source_hash: str
    ) -> GraphEdge | None:
        """02-business-invariants: "fromNodeId + toNodeId + edgeType + sourceHash 唯一，
        防止同一证据重复建边."
        """
        ...

    def save(self, edge: GraphEdge) -> GraphEdge: ...

    def find_adjacent(self, node_id: GraphNodeId, limit: int) -> list[GraphEdge]:
        """ExpandKnowledgeGraphService's bounded-BFS building block — only VISIBLE edges
        (02-business-invariants: HIDDEN/TOMBSTONED must not participate in default
        expansion).
        """
        ...


class ProcessedEventRepository(Protocol):
    """02-business-invariants (SPEC-MK-001 domain-rules): "所有消费事件必须
    processed-event 去重." Not yet called by any use case in this spec's own scope (no
    consumed event exists here — that begins phase-03, memory-candidate-pipeline), wired
    ahead of its first consumer the same way agent-runtime-service's own SPEC-ARO-001
    defined ProcessedEventRepository before SPEC-ARO-005 first used it.
    """

    def is_processed(self, event_id: str, consumer_name: str) -> bool: ...

    def mark_processed(self, event_id: str, consumer_name: str, processed_at: datetime, event_type: str | None = None) -> None: ...


class OutboxRepository(Protocol):
    """SPEC-MK-001 domain-rules: "事件发布必须经过 Memory outbox." DispatchOutboxEventsService
    publishes through EventPublisherPort, never directly. Real broker wiring
    (RabbitMQ) is deferred past SPEC-MK-003 too — see infrastructure.event_publisher's
    own module docstring; this port's own job (durable outbox row lifecycle, including
    the SPEC-MK-003 09-concurrency-and-idempotency §"Outbox 幂等": "replay 必须幂等"
    requeue() below) is complete independent of which publisher adapter is wired.
    """

    def append(self, record: OutboxRecord) -> None: ...

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]: ...

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None: ...

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None: ...

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None: ...

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]: ...

    def requeue(self, outbox_id: uuid.UUID, available_at: datetime) -> None:
        """SPEC-MK-003 08-transaction-and-outbox §"Outbox Publisher": "replay 必须幂等" —
        moves a DEAD_LETTER row back to PENDING with attempts reset to 0 and
        available_at reset to `available_at`, so DispatchOutboxEventsService's existing
        publish/retry/backoff cycle can pick the row back up exactly as it would a
        fresh one. Mirrors agent-runtime-service's own OutboxRepository.requeue().
        """
        ...


class CommandIdempotencyRepository(Protocol):
    """SPEC-MK-003 09-concurrency-and-idempotency §"Command Idempotency"."""

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None: ...

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord: ...


class AuditRecordRepository(Protocol):
    """SPEC-MK-003 12-observability §"Audit Events": "审计事件必须可长期保存." Append-only —
    no mark_*/update method, mirroring PoisonEventRepository-style append-only ports in
    agent-runtime-service (this domain has no poison-event table of its own — see
    domain-rules for why that's out of this spec's LLD mapping).
    """

    def append(self, entry: AuditRecordEntry) -> None: ...

    def find_recent(self, limit: int) -> list[AuditRecordEntry]:
        """Newest first — the admin visibility surface, mirroring
        RetrievalLogRepository.find_recent()'s own shape.
        """
        ...


class EmbeddingProvider(Protocol):
    """Computes a vector from text — the infrastructure.embedding adapter. A real
    provider (OpenAI/local model) is out of this spec's scope; SPEC-MK-001 ships a
    deterministic, honestly-labeled placeholder (see infrastructure.embedding's own
    module docstring for why it never claims to be a trained model).
    """

    def embed(self, text: str) -> tuple[EmbeddingRef, tuple[float, ...]]: ...


class RedactionPolicyPort(Protocol):
    """02-business-invariants (SPEC-MK-001 domain-rules): "敏感数据必须脱敏或拒绝." Every
    piece of text destined for candidate_text/redacted_text, MemoryVersion.content, or a
    DocumentChunk must pass through this first.
    """

    def redact(self, text: str) -> tuple[str, RedactionReport]: ...


class AuthorizationPort(Protocol):
    """02-business-invariants §"安全不变量": "高敏 classification 的 memory 默认不可跨
    queue / role 检索"; "所有 admin override 必须审计."
    """

    def is_retrieval_authorized(self, access_scope: AccessScope, classification: str) -> bool: ...

    def is_deletion_authorized(self, actor_id: str, memory_id: MemoryId) -> bool: ...

    def is_conflict_resolution_authorized(self, actor_id: str, candidate_id: MemoryCandidateId) -> bool:
        """02-business-invariants §"记忆写入不变量": "CONFLICTING candidate 必须人工或
        policy 处理，不能自动覆盖 active memory" — consulted before
        PublishMemoryService approves a CONFLICTING candidate, the same way
        is_deletion_authorized already gates ExecuteRetentionService's own
        sensitive override.
        """
        ...

    def is_organizational_relation_authorized(self, access_scope: AccessScope) -> bool:
        """11-security §"Graph Security": "OWNED_BY / POLICY_RULE 等组织关系默认只对
        authorized role 返回" — a floor independent of whatever classification an
        OWNED_BY edge or POLICY_RULE node happens to carry (ExpandKnowledgeGraphService
        already checks classification separately for every node); consulted in
        addition to that check, never instead of it.
        """
        ...


class TicketSnapshotPort(Protocol):
    """02-business-invariants §"状态所有权": read-only view of Ticket state; never a
    write path.
    """

    def find_snapshot(self, ticket_id: TicketId) -> TicketSnapshot | None: ...


class WorkflowTracePort(Protocol):
    """02-business-invariants §"状态所有权": read-only view of Agent Runtime automation
    trace; never a write path.
    """

    def find_trace(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowTrace | None: ...


class EventPublisherPort(Protocol):
    """08-transaction-and-outbox (deferred detail to SPEC-MK-003) §"Outbox Publisher".
    Memory Knowledge's only exit for publishing — only DispatchOutboxEventsService may
    depend on this port.
    """

    def publish(self, record: OutboxRecord) -> bool:
        """Returns True on success. Must never raise for an ordinary delivery failure —
        DispatchOutboxEventsService interprets False as "retry with backoff".
        """
        ...


class DocumentParserPort(Protocol):
    """13-package-and-class-design lists `infrastructure/document_parser.py` without
    naming a matching output port explicitly — added here the same way
    CommandIdempotencyRepository was: memoryknowledge.application must not import
    memoryknowledge.infrastructure directly (the import-linter "forbidden" contract), so
    IngestKnowledgeDocumentService can only reach the parser/chunker through a port.
    """

    def parse_and_chunk(self, raw_content: str, document_id: KnowledgeDocumentId, document_version: int) -> list[DocumentChunk]: ...


class EntityExtractorPort(Protocol):
    """13-package-and-class-design: "Entity extractor 只能基于 redacted content 和
    evidenceRefs 建图" — the signature itself makes an extractor that reads raw,
    unredacted content unrepresentable: only redacted_text ever reaches this port.
    """

    def extract(self, redacted_text: str, evidence_refs: tuple[SourceRef, ...]) -> tuple[list[GraphEntityInput], list[GraphRelationInput]]: ...


class GraphRerankerPort(Protocol):
    """01-domain-model §"Graph 如何使用" step 4: "根据 edge type、confidence、recency、
    source trust rerank."
    """

    def rerank(self, results: tuple[RetrievalResultItem, ...], graph_paths: tuple[GraphPath, ...]) -> tuple[RetrievalResultItem, ...]: ...
