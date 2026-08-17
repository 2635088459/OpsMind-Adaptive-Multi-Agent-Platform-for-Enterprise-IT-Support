from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from memoryknowledge.application.services.recover_memory_operations import RecoverMemoryOperationsService
from memoryknowledge.domain.enums import GraphNodeStatus, GraphNodeType, MemoryType
from memoryknowledge.domain.ids import GraphNodeId, KnowledgeDocumentId, MemoryId, MemoryVersionId
from memoryknowledge.domain.knowledge_document import KnowledgeDocument
from memoryknowledge.domain.knowledge_graph import GraphNode
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.values import RedactionReport, SourceRef
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryGraphEdgeRepository,
    InMemoryGraphNodeRepository,
    InMemoryKnowledgeDocumentRepository,
    InMemoryMemoryRepository,
)

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_service(document_repository=None, memory_repository=None, graph_node_repository=None, graph_edge_repository=None):
    return RecoverMemoryOperationsService(
        document_repository or InMemoryKnowledgeDocumentRepository(),
        memory_repository or InMemoryMemoryRepository(),
        graph_node_repository or InMemoryGraphNodeRepository(),
        graph_edge_repository or InMemoryGraphEdgeRepository(),
        SystemClockAdapter(),
        InMemoryAuditRecordRepository(),
    )


def test_scan_and_recover_ingestion_fails_a_document_stuck_mid_pipeline() -> None:
    """A document still sitting in CHUNKED long after created_at can only mean the
    ingest() call that produced it crashed before reaching ACTIVE/FAILED.
    """
    document_repository = InMemoryKnowledgeDocumentRepository()
    stuck_created_at = _now() - timedelta(hours=1)
    document = KnowledgeDocument.receive(
        KnowledgeDocumentId.new_id(), "confluence", "KB-STUCK", "Stuck Runbook", "RUNBOOK", (), 1, "hash-1", stuck_created_at,
    )
    document = document_repository.save(document, expected_status=None)
    document = document.mark_parsed()
    document = document_repository.save(document, expected_status="RECEIVED")
    document = document.mark_chunked()
    document = document_repository.save(document, expected_status="PARSED")

    service = _build_service(document_repository=document_repository)
    report = service.scan_and_recover_ingestion(batch_size=10)

    assert report.scanned == 1
    assert report.recovered == 1
    recovered = document_repository.find_by_id(document.document_id)
    assert recovered.ingestion_status.name == "FAILED"
    assert recovered.failure_reason is not None


def test_scan_and_recover_ingestion_leaves_a_recent_non_terminal_document_alone() -> None:
    """A document created moments ago is presumed to still be a genuinely in-flight
    request, not a crash — see find_stuck()'s own grace-period reasoning.
    """
    document_repository = InMemoryKnowledgeDocumentRepository()
    document = KnowledgeDocument.receive(
        KnowledgeDocumentId.new_id(), "confluence", "KB-FRESH", "Fresh Runbook", "RUNBOOK", (), 1, "hash-2", _now(),
    )
    document_repository.save(document, expected_status=None)

    service = _build_service(document_repository=document_repository)
    report = service.scan_and_recover_ingestion(batch_size=10)

    assert report.scanned == 0
    assert report.recovered == 0


def test_scan_and_recover_publish_graph_repairs_an_active_version_missing_its_graph_node() -> None:
    """A crash between PublishMemoryService's own version-save and its graph-upsert
    step leaves an ACTIVE MemoryVersion with no MEMORY_VERSION graph node at all.
    """
    memory_repository = InMemoryMemoryRepository()
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now(), classification="INTERNAL")
    memory = memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        memory_version_id=MemoryVersionId.new_id(), memory_id=memory.memory_id, version=1,
        content="vpn login fails after mfa reset", summary="vpn mfa loop",
        source_refs=(SourceRef(source_type="ticket", source_id="T-1"),), redaction_report=RedactionReport(),
        confidence_score=0.8, source_trust_score=0.9, source_hash="hash-x", created_by="admin-1", created_at=_now(),
    )
    memory_repository.save_version(version, expected_status=None)

    graph_node_repository = InMemoryGraphNodeRepository()
    service = _build_service(memory_repository=memory_repository, graph_node_repository=graph_node_repository)

    report = service.scan_and_recover_publish_graph(batch_size=10)

    assert report.scanned == 1
    assert report.recovered == 1
    assert graph_node_repository.find_by_stable_key(f"memory:{memory.memory_id}", GraphNodeType.MEMORY) is not None
    assert graph_node_repository.find_by_stable_key(f"memory_version:{version.memory_version_id}", GraphNodeType.MEMORY_VERSION) is not None


def test_scan_and_recover_publish_graph_is_a_no_op_when_the_node_already_exists() -> None:
    memory_repository = InMemoryMemoryRepository()
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now(), classification="INTERNAL")
    memory = memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        memory_version_id=MemoryVersionId.new_id(), memory_id=memory.memory_id, version=1,
        content="content", summary="summary", source_refs=(SourceRef(source_type="ticket", source_id="T-1"),),
        redaction_report=RedactionReport(), confidence_score=0.8, source_trust_score=0.9, source_hash="hash-y",
        created_by="admin-1", created_at=_now(),
    )
    memory_repository.save_version(version, expected_status=None)

    graph_node_repository = InMemoryGraphNodeRepository()
    graph_node_repository.save(GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.MEMORY_VERSION, f"memory_version:{version.memory_version_id}", "summary",
        "INTERNAL", (SourceRef(source_type="ticket", source_id="T-1"),), _now(),
    ))

    service = _build_service(memory_repository=memory_repository, graph_node_repository=graph_node_repository)
    report = service.scan_and_recover_publish_graph(batch_size=10)

    assert report.scanned == 1
    assert report.recovered == 0


def test_scan_and_recover_retention_tombstones_a_memory_node_whose_versions_are_all_deleted() -> None:
    """A crash between ExecuteRetentionService._do_delete()'s own version-delete loop
    (which runs first) and its final graph-tombstone step.
    """
    memory_repository = InMemoryMemoryRepository()
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now(), classification="INTERNAL")
    memory = memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        memory_version_id=MemoryVersionId.new_id(), memory_id=memory.memory_id, version=1,
        content="content", summary="summary", source_refs=(SourceRef(source_type="ticket", source_id="T-1"),),
        redaction_report=RedactionReport(), confidence_score=0.8, source_trust_score=0.9, source_hash="hash-z",
        created_by="admin-1", created_at=_now(),
    )
    version = memory_repository.save_version(version, expected_status=None)
    deleted_version = version.delete()
    memory_repository.save_version(deleted_version, expected_status=version.status)

    graph_node_repository = InMemoryGraphNodeRepository()
    node = graph_node_repository.save(GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.MEMORY, f"memory:{memory.memory_id}", "summary",
        "INTERNAL", (SourceRef(source_type="ticket", source_id="T-1"),), _now(),
    ))

    service = _build_service(memory_repository=memory_repository, graph_node_repository=graph_node_repository)
    report = service.scan_and_recover_retention(batch_size=10)

    assert report.scanned == 1
    assert report.recovered == 1
    recovered_node = graph_node_repository.find_by_id(node.node_id)
    assert recovered_node.status is GraphNodeStatus.TOMBSTONED


def test_scan_and_recover_retention_leaves_a_memory_with_a_remaining_active_version_alone() -> None:
    memory_repository = InMemoryMemoryRepository()
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now(), classification="INTERNAL")
    memory = memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        memory_version_id=MemoryVersionId.new_id(), memory_id=memory.memory_id, version=1,
        content="content", summary="summary", source_refs=(SourceRef(source_type="ticket", source_id="T-1"),),
        redaction_report=RedactionReport(), confidence_score=0.8, source_trust_score=0.9, source_hash="hash-w",
        created_by="admin-1", created_at=_now(),
    )
    memory_repository.save_version(version, expected_status=None)

    graph_node_repository = InMemoryGraphNodeRepository()
    node = graph_node_repository.save(GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.MEMORY, f"memory:{memory.memory_id}", "summary",
        "INTERNAL", (SourceRef(source_type="ticket", source_id="T-1"),), _now(),
    ))

    service = _build_service(memory_repository=memory_repository, graph_node_repository=graph_node_repository)
    report = service.scan_and_recover_retention(batch_size=10)

    assert report.scanned == 1
    assert report.recovered == 0
    assert graph_node_repository.find_by_id(node.node_id).status is GraphNodeStatus.VISIBLE
