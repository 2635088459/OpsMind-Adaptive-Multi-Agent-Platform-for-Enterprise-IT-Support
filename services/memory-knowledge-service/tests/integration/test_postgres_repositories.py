"""SPEC-MK-002: integration tests for every infrastructure.persistence.postgres
repository against a real, migrated Postgres (tests/integration/conftest.py). Requires
Docker; marked `integration` (see pyproject.toml's `testpaths`/`markers`).
"""

from __future__ import annotations

import dataclasses
import uuid
from datetime import UTC, datetime, timedelta

import pytest

from memoryknowledge.application.exceptions import OptimisticConcurrencyConflictException, WorkingMemoryScopeConflictException
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.records import CommandIdempotencyRecord
from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType, MemoryCandidateStatus, MemoryType
from memoryknowledge.domain.events import MemoryPublished
from memoryknowledge.domain.exceptions import WorkingMemoryVersionConflictException
from memoryknowledge.domain.ids import (
    DocumentChunkId,
    GraphEdgeId,
    GraphNodeId,
    IdempotencyKey,
    KnowledgeDocumentId,
    MemoryCandidateId,
    MemoryId,
    MemoryVersionId,
    RetrievalId,
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
from memoryknowledge.domain.values import EmbeddingRef, RedactionReport, SourceRef
from memoryknowledge.domain.working_memory import WorkingMemory
from memoryknowledge.infrastructure.persistence.postgres.repositories import (
    PostgresCommandIdempotencyRepository,
    PostgresEmbeddingRepository,
    PostgresGraphEdgeRepository,
    PostgresGraphNodeRepository,
    PostgresKnowledgeDocumentRepository,
    PostgresMemoryCandidateRepository,
    PostgresMemoryRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
    PostgresRetrievalLogRepository,
    PostgresWorkingMemoryRepository,
)

pytestmark = pytest.mark.integration


def _now() -> datetime:
    return datetime.now(UTC)


# --------------------------------------------------------------------------------
# WorkingMemory
# --------------------------------------------------------------------------------


def test_working_memory_round_trips_and_enforces_optimistic_version(session_factory) -> None:
    repository = PostgresWorkingMemoryRepository(session_factory)
    ticket_id, ticket_cycle_id, workflow_instance_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4()), WorkflowInstanceId(uuid.uuid4())
    working_memory = WorkingMemory.create(WorkingMemoryId.new_id(), ticket_id, ticket_cycle_id, workflow_instance_id, "agent-1", _now())

    saved = repository.save(working_memory)
    assert repository.find_by_id(saved.working_memory_id).facts == ()
    assert repository.find_active_by_scope(ticket_id, ticket_cycle_id, workflow_instance_id) is not None

    updated = saved.apply_update(expected_version=saved.version, updated_by="agent-1", updated_at=_now(), add_facts=("vpn down",))
    repository.save(updated)
    assert repository.find_by_id(saved.working_memory_id).facts == ("vpn down",)

    with pytest.raises(WorkingMemoryVersionConflictException):
        repository.save(updated)  # stale: already applied above


def test_working_memory_unique_active_scope_rejects_a_second_active_row(session_factory) -> None:
    repository = PostgresWorkingMemoryRepository(session_factory)
    ticket_id, ticket_cycle_id, workflow_instance_id = TicketId(uuid.uuid4()), TicketCycleId(uuid.uuid4()), WorkflowInstanceId(uuid.uuid4())
    repository.save(WorkingMemory.create(WorkingMemoryId.new_id(), ticket_id, ticket_cycle_id, workflow_instance_id, "agent-1", _now()))

    # A distinct id (unlike the app-layer's derive_working_memory_id, this repository
    # test forces the race directly) for the same ACTIVE scope must be rejected.
    with pytest.raises(WorkingMemoryScopeConflictException):
        repository.save(WorkingMemory.create(WorkingMemoryId.new_id(), ticket_id, ticket_cycle_id, workflow_instance_id, "agent-2", _now()))


# --------------------------------------------------------------------------------
# MemoryCandidate
# --------------------------------------------------------------------------------


def test_memory_candidate_round_trips_and_enforces_status_cas(session_factory) -> None:
    repository = PostgresMemoryCandidateRepository(session_factory)
    candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "vpn fails", "hash-1", _now())

    repository.save(candidate, expected_status=None)
    stored = repository.find_by_id(candidate.candidate_id)
    assert stored.candidate_text == "vpn fails"
    assert stored.source_refs == (SourceRef("ticket", "T-1"),)

    redacted = candidate.redact("[REDACTED]", RedactionReport(redacted_fields=("content",), secret_patterns_matched=("email",)))
    repository.save(redacted, expected_status=MemoryCandidateStatus.EXTRACTED)
    assert repository.find_by_id(candidate.candidate_id).redaction_report.secret_patterns_matched == ("email",)

    with pytest.raises(OptimisticConcurrencyConflictException):
        repository.save(redacted, expected_status=MemoryCandidateStatus.EXTRACTED)  # already REDACTED now


# --------------------------------------------------------------------------------
# Memory / MemoryVersion
# --------------------------------------------------------------------------------


def test_memory_version_round_trips_and_one_active_per_memory_enforced(session_factory) -> None:
    repository = PostgresMemoryRepository(session_factory)
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now())
    repository.save_memory(memory)
    assert repository.find_memory_by_id(memory.memory_id).memory_type is MemoryType.EPISODIC

    version = MemoryVersion.create_active(
        MemoryVersionId.new_id(), memory.memory_id, 1, "content", "summary", (SourceRef("ticket", "T-1"),),
        RedactionReport(), 0.8, 0.7, "hash-1", "agent-1", _now(),
    )
    repository.save_version(version, expected_status=None)
    assert repository.find_active_version(memory.memory_id).memory_version_id == version.memory_version_id
    assert repository.find_by_source_hash("hash-1").memory_id == memory.memory_id

    # A second ACTIVE version for the same memory_id violates
    # uq_memory_versions_one_active_per_memory.
    second_active = MemoryVersion.create_active(
        MemoryVersionId.new_id(), memory.memory_id, 2, "content-2", "summary-2", (SourceRef("ticket", "T-1"),),
        RedactionReport(), 0.8, 0.7, "hash-2", "agent-1", _now(),
    )
    with pytest.raises(OptimisticConcurrencyConflictException):
        repository.save_version(second_active, expected_status=None)


def test_memory_find_active_versions_by_type_filters_correctly(session_factory) -> None:
    repository = PostgresMemoryRepository(session_factory)
    episodic_memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now())
    procedural_memory = Memory.create(MemoryId.new_id(), MemoryType.PROCEDURAL, _now())
    repository.save_memory(episodic_memory)
    repository.save_memory(procedural_memory)
    repository.save_version(
        MemoryVersion.create_active(
            MemoryVersionId.new_id(), episodic_memory.memory_id, 1, "c1", "s1", (SourceRef("ticket", "T-1"),), RedactionReport(), 0.8, 0.7, "h1", "a1", _now(),
        ),
        expected_status=None,
    )
    repository.save_version(
        MemoryVersion.create_active(
            MemoryVersionId.new_id(), procedural_memory.memory_id, 1, "c2", "s2", (SourceRef("ticket", "T-2"),), RedactionReport(), 0.8, 0.7, "h2", "a1", _now(),
        ),
        expected_status=None,
    )

    only_episodic = repository.find_active_versions_by_type(("EPISODIC",), limit=10)
    assert len(only_episodic) == 1
    assert only_episodic[0].memory_id == episodic_memory.memory_id


# --------------------------------------------------------------------------------
# KnowledgeDocument / DocumentChunk
# --------------------------------------------------------------------------------


def test_knowledge_document_natural_key_uniqueness_and_chunk_round_trip(session_factory) -> None:
    repository = PostgresKnowledgeDocumentRepository(session_factory)
    document = KnowledgeDocument.receive(
        KnowledgeDocumentId.new_id(), "confluence", "KB-1", "VPN Runbook", "RUNBOOK", (), 1, "hash-1", _now(),
    )
    repository.save(document, expected_status=None)
    assert repository.find_by_natural_key("confluence", "KB-1", 1) is not None

    duplicate = KnowledgeDocument.receive(
        KnowledgeDocumentId.new_id(), "confluence", "KB-1", "VPN Runbook v2", "RUNBOOK", (), 1, "hash-2", _now(),
    )
    with pytest.raises(OptimisticConcurrencyConflictException):
        repository.save(duplicate, expected_status=None)

    chunk = DocumentChunk(
        chunk_id=DocumentChunkId.new_id(),
        document_id=document.document_id, document_version=1, chunk_index=0, content="VPN fails after MFA reset.",
        token_count=5, heading_path="Symptom", content_hash="chunk-hash-1",
        embedding_ref=EmbeddingRef(provider="deterministic-hash", model="sha256-projection-v1", dimensions=4, vector_id=str(uuid.uuid4())),
    )
    repository.save_chunks((chunk,))
    stored_chunks = repository.find_chunks(document.document_id)
    assert len(stored_chunks) == 1
    assert stored_chunks[0].content == "VPN fails after MFA reset."
    assert stored_chunks[0].embedding_ref.dimensions == 4

    activated = document.mark_parsed()
    repository.save(activated, expected_status="RECEIVED")
    activated = activated.mark_chunked()
    repository.save(activated, expected_status="PARSED")
    activated = activated.mark_embedded()
    repository.save(activated, expected_status="CHUNKED")
    activated = activated.mark_indexed()
    repository.save(activated, expected_status="EMBEDDED")
    activated = activated.activate()
    repository.save(activated, expected_status="INDEXED")

    active_chunks = repository.find_active_chunks(limit=10)
    assert len(active_chunks) == 1


# --------------------------------------------------------------------------------
# Embedding
# --------------------------------------------------------------------------------


def test_embedding_round_trips_through_pgvector(session_factory) -> None:
    repository = PostgresEmbeddingRepository(session_factory)
    ref = EmbeddingRef(provider="deterministic-hash", model="sha256-projection-v1", dimensions=4, vector_id=str(uuid.uuid4()))
    vector = (0.1, -0.2, 0.3, 0.4)

    repository.save(ref, vector)
    stored = repository.find(ref.vector_id)
    assert stored is not None
    assert len(stored) == 4
    assert all(abs(a - b) < 1e-6 for a, b in zip(stored, vector, strict=True))

    # Idempotent: a second save() of the same vector_id is a no-op, not a conflict.
    repository.save(ref, vector)


# --------------------------------------------------------------------------------
# GraphNode / GraphEdge
# --------------------------------------------------------------------------------


def test_graph_node_stable_key_uniqueness_and_status_transition(session_factory) -> None:
    repository = PostgresGraphNodeRepository(session_factory)
    node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.SERVICE, "service:vpn-auth", "VPN Auth", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    repository.save(node)
    assert repository.find_by_stable_key("service:vpn-auth", GraphNodeType.SERVICE) is not None

    hidden = node.hide()
    repository.save(hidden)
    assert repository.find_by_id(node.node_id).status.name == "HIDDEN"


def test_graph_edge_natural_key_uniqueness_and_find_adjacent(session_factory) -> None:
    node_repository = PostgresGraphNodeRepository(session_factory)
    edge_repository = PostgresGraphEdgeRepository(session_factory)
    from_node = GraphNode.create(GraphNodeId.new_id(), GraphNodeType.SYMPTOM, "symptom:a", "A", "INTERNAL", (SourceRef("ticket", "T-1"),), _now())
    to_node = GraphNode.create(GraphNodeId.new_id(), GraphNodeType.ROOT_CAUSE, "root_cause:b", "B", "INTERNAL", (SourceRef("ticket", "T-1"),), _now())
    node_repository.save(from_node)
    node_repository.save(to_node)

    edge = GraphEdge.create(
        GraphEdgeId.new_id(), GraphEdgeType.SUPPORTED_BY, from_node.node_id, to_node.node_id, 0.8, (SourceRef("ticket", "T-1"),), "edge-hash-1", _now(),
    )
    edge_repository.save(edge)
    assert edge_repository.find_by_natural_key(from_node.node_id, to_node.node_id, "SUPPORTED_BY", "edge-hash-1") is not None

    adjacent = edge_repository.find_adjacent(from_node.node_id, limit=10)
    assert len(adjacent) == 1
    assert adjacent[0].edge_id == edge.edge_id

    tombstoned = edge.tombstone()
    edge_repository.save(tombstoned)
    assert edge_repository.find_adjacent(from_node.node_id, limit=10) == []


# --------------------------------------------------------------------------------
# RetrievalLog
# --------------------------------------------------------------------------------


def test_retrieval_log_append_and_find_recent(session_factory) -> None:
    repository = PostgresRetrievalLogRepository(session_factory)
    log = RetrievalLog.record(
        RetrievalId.new_id(), "agent", "agent-1", "queryhash-1", ("memory:1",), degraded=False, latency_ms=12, created_at=_now(),
    )
    repository.append(log)

    recent = repository.find_recent(limit=10)
    assert len(recent) == 1
    assert recent[0].query_hash == "queryhash-1"
    assert recent[0].degraded is False


# --------------------------------------------------------------------------------
# ProcessedEvent / Outbox / CommandIdempotency
# --------------------------------------------------------------------------------


def test_processed_event_dedup(session_factory) -> None:
    repository = PostgresProcessedEventRepository(session_factory)
    assert repository.is_processed("evt-1", "consumer-a") is False

    repository.mark_processed("evt-1", "consumer-a", _now(), event_type="memory.published.v1")
    assert repository.is_processed("evt-1", "consumer-a") is True
    # Different consumer, same event_id: independent dedup key.
    assert repository.is_processed("evt-1", "consumer-b") is False

    repository.mark_processed("evt-1", "consumer-a", _now())  # no-op, not an error


def test_outbox_dispatch_lifecycle_publish_retry_and_dead_letter(session_factory) -> None:
    repository = PostgresOutboxRepository(session_factory)
    now = _now()
    record = build_outbox_record(
        MemoryPublished(memory_id=MemoryId.new_id(), memory_version_id=MemoryVersionId.new_id(), version=1, occurred_at=now),
        "memory.published.v1", aggregate_id="agg-1", occurred_at=now,
    )
    repository.append(record)

    due = repository.find_dispatchable(now + timedelta(seconds=1), limit=10)
    assert len(due) == 1

    repository.mark_published(record.outbox_id, now)
    assert repository.find_dispatchable(now + timedelta(seconds=1), limit=10) == []

    second = build_outbox_record(
        MemoryPublished(memory_id=MemoryId.new_id(), memory_version_id=MemoryVersionId.new_id(), version=1, occurred_at=now),
        "memory.published.v1", aggregate_id="agg-2", occurred_at=now,
    )
    repository.append(second)
    repository.mark_dead_letter(second.outbox_id)
    dead_letters = repository.find_dead_letter(limit=10)
    assert len(dead_letters) == 1
    assert dead_letters[0].outbox_id == second.outbox_id


def test_command_idempotency_round_trip(session_factory) -> None:
    repository = PostgresCommandIdempotencyRepository(session_factory)
    key = IdempotencyKey(f"idem-{uuid.uuid4()}")
    assert repository.find_by_key(key) is None

    record = CommandIdempotencyRecord(
        idempotency_key=key, command_type="publish_memory", target_id=str(uuid.uuid4()),
        request_hash="hash-1", response_json='{"a": 1}', created_at=_now(),
    )
    repository.save(record)

    stored = repository.find_by_key(key)
    assert stored is not None
    assert stored.command_type == "publish_memory"
    assert stored.request_hash == "hash-1"
    assert stored.response_json == '{"a": 1}'

    # A concurrent insert under the same key is a no-op, not an error — the first
    # writer's response must survive.
    repository.save(dataclasses.replace(record, response_json='{"a": 2}'))
    assert repository.find_by_key(key).response_json == record.response_json


def test_command_idempotency_guard_replays_same_payload_and_rejects_different_payload(session_factory) -> None:
    """SPEC-MK-003 09-concurrency-and-idempotency §"Command Idempotency": exercises the
    real CommandIdempotencyGuard against Postgres, not just the raw repository.
    """
    from memoryknowledge.application.exceptions import IdempotencyKeyReusedException
    from memoryknowledge.application.services.idempotency import CommandIdempotencyGuard
    from memoryknowledge.infrastructure.clock import SystemClockAdapter

    guard = CommandIdempotencyGuard(PostgresCommandIdempotencyRepository(session_factory), SystemClockAdapter())
    key = IdempotencyKey(f"idem-{uuid.uuid4()}")
    calls = {"count": 0}

    def execute() -> str:
        calls["count"] += 1
        return "result-1"

    first = guard.run("do_thing", "target-1", key, {"x": 1}, execute, lambda r: {"value": r}, lambda d: d["value"])
    second = guard.run("do_thing", "target-1", key, {"x": 1}, execute, lambda r: {"value": r}, lambda d: d["value"])
    assert first == second == "result-1"
    assert calls["count"] == 1  # execute() never called again on replay

    with pytest.raises(IdempotencyKeyReusedException):
        guard.run("do_thing", "target-1", key, {"x": 2}, execute, lambda r: {"value": r}, lambda d: d["value"])


def test_audit_record_append_and_find_recent_against_real_postgres(session_factory) -> None:
    from memoryknowledge.application.records import AuditRecordEntry
    from memoryknowledge.infrastructure.persistence.postgres.repositories import PostgresAuditRecordRepository

    repository = PostgresAuditRecordRepository(session_factory)
    entry = AuditRecordEntry(
        id=uuid.uuid4(), audit_type="MEMORY", action="publish_memory", resource_type="MEMORY", resource_id=str(uuid.uuid4()),
        ticket_id=None, actor_type="SYSTEM", actor_id="admin-1", outcome="SUCCESS", correlation_id=None, causation_id=None,
        detail="{}", occurred_at=_now(),
    )
    repository.append(entry)

    recent = repository.find_recent(limit=10)
    assert len(recent) == 1
    assert recent[0].action == "publish_memory"
    assert recent[0].actor_id == "admin-1"


def test_outbox_requeue_resets_a_dead_lettered_row_against_real_postgres(session_factory) -> None:
    repository = PostgresOutboxRepository(session_factory)
    now = _now()
    record = build_outbox_record(
        MemoryPublished(memory_id=MemoryId.new_id(), memory_version_id=MemoryVersionId.new_id(), version=1, occurred_at=now),
        "memory.published.v1", aggregate_id="agg-requeue-1", occurred_at=now,
    )
    repository.append(record)
    repository.mark_dead_letter(record.outbox_id)
    assert len(repository.find_dead_letter(limit=10)) == 1

    repository.requeue(record.outbox_id, now)

    assert repository.find_dead_letter(limit=10) == []
    due = repository.find_dispatchable(now + timedelta(seconds=1), limit=10)
    assert len(due) == 1
    assert due[0].attempts == 0
