"""SPEC-MK-001-scoped in-memory adapters for every memoryknowledge.application.
ports_out repository — mirrors agent-runtime-service's own
infrastructure.persistence.in_memory exactly: fast, hermetic, used directly by unit
tests and by memoryknowledge.container when Settings.memory_persistence == "memory".
SPEC-MK-002 (schema baseline) adds the real Postgres-backed adapters alongside these.
"""

from __future__ import annotations

import dataclasses
import uuid
from datetime import datetime

from memoryknowledge.application.exceptions import OptimisticConcurrencyConflictException, WorkingMemoryScopeConflictException
from memoryknowledge.application.records import AuditRecordEntry, CommandIdempotencyRecord, OutboxRecord
from memoryknowledge.domain.enums import GraphNodeStatus, MemoryVersionStatus, OutboxStatus
from memoryknowledge.domain.exceptions import WorkingMemoryVersionConflictException
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
from memoryknowledge.domain.values import EmbeddingRef
from memoryknowledge.domain.working_memory import WorkingMemory


class InMemoryWorkingMemoryRepository:
    def __init__(self) -> None:
        self._by_id: dict[WorkingMemoryId, WorkingMemory] = {}
        self._active_by_scope: dict[tuple[uuid.UUID, uuid.UUID, uuid.UUID], WorkingMemoryId] = {}

    @staticmethod
    def _scope_key(ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_instance_id: WorkflowInstanceId):
        return (ticket_id.value, ticket_cycle_id.value, workflow_instance_id.value)

    def find_by_id(self, working_memory_id: WorkingMemoryId) -> WorkingMemory | None:
        return self._by_id.get(working_memory_id)

    def find_active_by_scope(self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_instance_id: WorkflowInstanceId) -> WorkingMemory | None:
        key = self._scope_key(ticket_id, ticket_cycle_id, workflow_instance_id)
        working_memory_id = self._active_by_scope.get(key)
        return self._by_id.get(working_memory_id) if working_memory_id is not None else None

    def save(self, working_memory: WorkingMemory) -> WorkingMemory:
        scope_key = self._scope_key(working_memory.ticket_id, working_memory.ticket_cycle_id, working_memory.workflow_instance_id)
        is_new = working_memory.working_memory_id not in self._by_id

        if is_new:
            if working_memory.status.name == "ACTIVE" and self._active_by_scope.get(scope_key) not in (None, working_memory.working_memory_id):
                raise WorkingMemoryScopeConflictException()
        else:
            stored = self._by_id[working_memory.working_memory_id]
            if stored.version != working_memory.version - 1:
                raise WorkingMemoryVersionConflictException(working_memory.version - 1, stored.version)

        self._by_id[working_memory.working_memory_id] = working_memory
        if working_memory.status.name == "ACTIVE":
            self._active_by_scope[scope_key] = working_memory.working_memory_id
        elif self._active_by_scope.get(scope_key) == working_memory.working_memory_id:
            del self._active_by_scope[scope_key]
        return working_memory


class InMemoryMemoryCandidateRepository:
    def __init__(self) -> None:
        self._by_id: dict[MemoryCandidateId, MemoryCandidate] = {}

    def find_by_id(self, candidate_id: MemoryCandidateId) -> MemoryCandidate | None:
        return self._by_id.get(candidate_id)

    def find_by_source_hash(self, source_hash: str, memory_type) -> MemoryCandidate | None:
        for candidate in self._by_id.values():
            if candidate.source_hash == source_hash and candidate.memory_type == memory_type:
                return candidate
        return None

    def save(self, candidate: MemoryCandidate, expected_status) -> MemoryCandidate:
        stored = self._by_id.get(candidate.candidate_id)
        if expected_status is None:
            if stored is not None:
                raise OptimisticConcurrencyConflictException("memory_candidate", str(candidate.candidate_id), None, stored.status.name)
        elif stored is None or stored.status != expected_status:
            raise OptimisticConcurrencyConflictException(
                "memory_candidate", str(candidate.candidate_id), expected_status.name if expected_status else None,
                stored.status.name if stored else None,
            )
        self._by_id[candidate.candidate_id] = candidate
        return candidate


class InMemoryMemoryRepository:
    def __init__(self) -> None:
        self._memories: dict[MemoryId, Memory] = {}
        self._versions: dict[MemoryVersionId, MemoryVersion] = {}
        self._versions_by_memory: dict[MemoryId, list[MemoryVersionId]] = {}
        self._active_by_source_hash: dict[str, MemoryVersionId] = {}

    def find_memory_by_id(self, memory_id: MemoryId) -> Memory | None:
        return self._memories.get(memory_id)

    def save_memory(self, memory: Memory) -> Memory:
        self._memories.setdefault(memory.memory_id, memory)
        return self._memories[memory.memory_id]

    def find_active_version(self, memory_id: MemoryId) -> MemoryVersion | None:
        for version_id in self._versions_by_memory.get(memory_id, []):
            version = self._versions[version_id]
            if version.status is MemoryVersionStatus.ACTIVE:
                return version
        return None

    def find_version_by_id(self, memory_version_id: MemoryVersionId) -> MemoryVersion | None:
        return self._versions.get(memory_version_id)

    def find_versions(self, memory_id: MemoryId) -> list[MemoryVersion]:
        return [self._versions[vid] for vid in self._versions_by_memory.get(memory_id, [])]

    def save_version(self, version: MemoryVersion, expected_status) -> MemoryVersion:
        stored = self._versions.get(version.memory_version_id)
        if expected_status is None:
            if stored is not None:
                raise OptimisticConcurrencyConflictException("memory_version", str(version.memory_version_id), None, stored.status.name)
            self._versions_by_memory.setdefault(version.memory_id, []).append(version.memory_version_id)
        elif stored is None or stored.status != expected_status:
            raise OptimisticConcurrencyConflictException(
                "memory_version", str(version.memory_version_id), expected_status.name if expected_status else None,
                stored.status.name if stored else None,
            )
        self._versions[version.memory_version_id] = version

        if version.status is MemoryVersionStatus.ACTIVE:
            self._active_by_source_hash[version.source_hash] = version.memory_version_id
        elif self._active_by_source_hash.get(version.source_hash) == version.memory_version_id:
            del self._active_by_source_hash[version.source_hash]
        return version

    def find_active_versions_by_type(self, memory_type_names: tuple[str, ...], limit: int) -> list[MemoryVersion]:
        results: list[MemoryVersion] = []
        for memory_id, version_ids in self._versions_by_memory.items():
            memory = self._memories.get(memory_id)
            if memory is None:
                continue
            if memory_type_names and memory.memory_type.name not in memory_type_names:
                continue
            for version_id in version_ids:
                version = self._versions[version_id]
                if version.status is MemoryVersionStatus.ACTIVE:
                    results.append(version)
                    break
            if len(results) >= limit:
                break
        return results[:limit]

    def find_by_source_hash(self, source_hash: str) -> MemoryVersion | None:
        version_id = self._active_by_source_hash.get(source_hash)
        return self._versions.get(version_id) if version_id is not None else None


class InMemoryKnowledgeDocumentRepository:
    def __init__(self) -> None:
        self._by_id: dict[KnowledgeDocumentId, KnowledgeDocument] = {}
        self._by_natural_key: dict[tuple[str, str, int], KnowledgeDocumentId] = {}
        self._chunks: dict[KnowledgeDocumentId, list[DocumentChunk]] = {}

    def find_by_id(self, document_id: KnowledgeDocumentId) -> KnowledgeDocument | None:
        return self._by_id.get(document_id)

    def find_by_natural_key(self, source_system: str, external_id: str, version: int) -> KnowledgeDocument | None:
        document_id = self._by_natural_key.get((source_system, external_id, version))
        return self._by_id.get(document_id) if document_id is not None else None

    def save(self, document: KnowledgeDocument, expected_status: str | None) -> KnowledgeDocument:
        stored = self._by_id.get(document.document_id)
        if expected_status is None:
            if stored is not None:
                raise OptimisticConcurrencyConflictException("knowledge_document", str(document.document_id), None, stored.ingestion_status.name)
            self._by_natural_key[(document.source_system, document.external_id, document.version)] = document.document_id
        elif stored is None or stored.ingestion_status.name != expected_status:
            raise OptimisticConcurrencyConflictException(
                "knowledge_document", str(document.document_id), expected_status, stored.ingestion_status.name if stored else None,
            )
        self._by_id[document.document_id] = document
        return document

    def save_chunks(self, chunks: tuple[DocumentChunk, ...]) -> None:
        for chunk in chunks:
            self._chunks.setdefault(chunk.document_id, []).append(chunk)

    def find_chunks(self, document_id: KnowledgeDocumentId) -> list[DocumentChunk]:
        return list(self._chunks.get(document_id, []))

    def find_active_chunks(self, limit: int) -> list[DocumentChunk]:
        results: list[DocumentChunk] = []
        for document in self._by_id.values():
            if document.ingestion_status.name != "ACTIVE":
                continue
            results.extend(self._chunks.get(document.document_id, []))
            if len(results) >= limit:
                break
        return results[:limit]


class InMemoryEmbeddingRepository:
    def __init__(self) -> None:
        self._vectors: dict[str, tuple[float, ...]] = {}

    def save(self, embedding_ref: EmbeddingRef, vector: tuple[float, ...]) -> None:
        self._vectors[embedding_ref.vector_id] = vector

    def find(self, vector_id: str) -> tuple[float, ...] | None:
        return self._vectors.get(vector_id)


class InMemoryRetrievalLogRepository:
    def __init__(self) -> None:
        self._logs: list[RetrievalLog] = []

    def append(self, log: RetrievalLog) -> None:
        self._logs.append(log)

    def find_recent(self, limit: int) -> list[RetrievalLog]:
        return list(reversed(self._logs[-limit:]))


class InMemoryGraphNodeRepository:
    def __init__(self) -> None:
        self._by_id: dict[GraphNodeId, GraphNode] = {}
        self._by_stable_key: dict[tuple[str, str], GraphNodeId] = {}

    def find_by_id(self, node_id: GraphNodeId) -> GraphNode | None:
        return self._by_id.get(node_id)

    def find_by_stable_key(self, stable_key: str, node_type) -> GraphNode | None:
        node_id = self._by_stable_key.get((stable_key, node_type.name))
        return self._by_id.get(node_id) if node_id is not None else None

    def save(self, node: GraphNode) -> GraphNode:
        self._by_id[node.node_id] = node
        self._by_stable_key[(node.stable_key, node.node_type.name)] = node.node_id
        return node

    def find_by_ids(self, node_ids: tuple[GraphNodeId, ...]) -> list[GraphNode]:
        return [self._by_id[node_id] for node_id in node_ids if node_id in self._by_id]


class InMemoryGraphEdgeRepository:
    def __init__(self) -> None:
        self._by_id: dict[GraphEdgeId, GraphEdge] = {}
        self._by_natural_key: dict[tuple[uuid.UUID, uuid.UUID, str, str], GraphEdgeId] = {}

    def find_by_id(self, edge_id: GraphEdgeId) -> GraphEdge | None:
        return self._by_id.get(edge_id)

    def find_by_natural_key(self, from_node_id: GraphNodeId, to_node_id: GraphNodeId, edge_type: str, source_hash: str) -> GraphEdge | None:
        edge_id = self._by_natural_key.get((from_node_id.value, to_node_id.value, edge_type, source_hash))
        return self._by_id.get(edge_id) if edge_id is not None else None

    def save(self, edge: GraphEdge) -> GraphEdge:
        self._by_id[edge.edge_id] = edge
        self._by_natural_key[(edge.from_node_id.value, edge.to_node_id.value, edge.edge_type.name, edge.source_hash)] = edge.edge_id
        return edge

    def find_adjacent(self, node_id: GraphNodeId, limit: int) -> list[GraphEdge]:
        matches = [
            edge for edge in self._by_id.values()
            if edge.status is GraphNodeStatus.VISIBLE and (edge.from_node_id == node_id or edge.to_node_id == node_id)
        ]
        return matches[:limit]


class InMemoryProcessedEventRepository:
    def __init__(self) -> None:
        self._processed: dict[tuple[str, str], tuple[datetime, str | None]] = {}

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        return (event_id, consumer_name) in self._processed

    def mark_processed(self, event_id: str, consumer_name: str, processed_at: datetime, event_type: str | None = None) -> None:
        self._processed[(event_id, consumer_name)] = (processed_at, event_type)


class InMemoryOutboxRepository:
    def __init__(self) -> None:
        self._records: dict[uuid.UUID, OutboxRecord] = {}

    def append(self, record: OutboxRecord) -> None:
        stored = record if record.available_at is not None else dataclasses.replace(record, available_at=record.occurred_at)
        self._records[stored.outbox_id] = stored

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        due = [
            r for r in self._records.values()
            if r.status is OutboxStatus.PENDING and r.available_at is not None and r.available_at <= now
        ]
        due.sort(key=lambda r: r.occurred_at)
        return due[:limit]

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None:
        record = self._records[outbox_id]
        self._records[outbox_id] = dataclasses.replace(record, status=OutboxStatus.PUBLISHED, published_at=published_at)

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None:
        record = self._records[outbox_id]
        self._records[outbox_id] = dataclasses.replace(record, status=OutboxStatus.PENDING, attempts=attempts, available_at=next_available_at)

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None:
        record = self._records[outbox_id]
        self._records[outbox_id] = dataclasses.replace(record, status=OutboxStatus.DEAD_LETTER)

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        return [r for r in self._records.values() if r.status is OutboxStatus.DEAD_LETTER][:limit]

    def requeue(self, outbox_id: uuid.UUID, available_at: datetime) -> None:
        record = self._records[outbox_id]
        self._records[outbox_id] = dataclasses.replace(
            record, status=OutboxStatus.PENDING, attempts=0, available_at=available_at, published_at=None,
        )

    def recorded(self) -> list[OutboxRecord]:
        """Test-only helper (mirrors agent-runtime-service's own
        InMemoryOutboxRepository.recorded()) — not part of OutboxRepository's Protocol.
        """
        return list(self._records.values())


class InMemoryCommandIdempotencyRepository:
    def __init__(self) -> None:
        self._by_key: dict[str, CommandIdempotencyRecord] = {}

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None:
        return self._by_key.get(idempotency_key.value)

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord:
        # First writer wins — a concurrent caller that already inserted this exact key
        # must not have its cached response clobbered (mirrors the Postgres adapter's
        # own IntegrityError-swallowing insert).
        self._by_key.setdefault(record.idempotency_key.value, record)
        return self._by_key[record.idempotency_key.value]


class InMemoryAuditRecordRepository:
    def __init__(self) -> None:
        self._entries: list[AuditRecordEntry] = []

    def append(self, entry: AuditRecordEntry) -> None:
        self._entries.append(entry)

    def find_recent(self, limit: int) -> list[AuditRecordEntry]:
        return list(reversed(self._entries[-limit:]))
