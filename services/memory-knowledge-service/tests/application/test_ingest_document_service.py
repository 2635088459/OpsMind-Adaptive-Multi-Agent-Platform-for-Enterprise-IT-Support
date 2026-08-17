from __future__ import annotations

import pytest

from memoryknowledge.application.commands import IngestKnowledgeDocumentCommand
from memoryknowledge.application.exceptions import DocumentAlreadyIngestedException, DocumentIngestionFailedException
from memoryknowledge.application.services.ingest_document import IngestKnowledgeDocumentService
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.document_parser import SimpleDocumentParserAdapter
from memoryknowledge.infrastructure.embedding.embedding_provider import DeterministicHashEmbeddingProvider
from memoryknowledge.infrastructure.graph.entity_extractor import MarkerBasedEntityExtractorAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryEmbeddingRepository,
    InMemoryGraphEdgeRepository,
    InMemoryGraphNodeRepository,
    InMemoryKnowledgeDocumentRepository,
    InMemoryOutboxRepository,
)
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter

pytestmark = pytest.mark.unit


def _build_service():
    document_repository = InMemoryKnowledgeDocumentRepository()
    outbox_repository = InMemoryOutboxRepository()
    graph_node_repository = InMemoryGraphNodeRepository()
    graph_edge_repository = InMemoryGraphEdgeRepository()
    service = IngestKnowledgeDocumentService(
        document_repository, SimpleDocumentParserAdapter(), RegexRedactionPolicyAdapter(), DeterministicHashEmbeddingProvider(),
        InMemoryEmbeddingRepository(), MarkerBasedEntityExtractorAdapter(), graph_node_repository, graph_edge_repository,
        outbox_repository, InMemoryAuditRecordRepository(), SystemClockAdapter(),
    )
    return service, document_repository, outbox_repository, graph_node_repository, graph_edge_repository


def _command(**overrides) -> IngestKnowledgeDocumentCommand:
    defaults = dict(
        source_system="confluence", external_id="KB-100", title="VPN Runbook", document_type="RUNBOOK", version=1,
        raw_content="## Symptom\nVPN login fails after MFA reset.\n\n## Fix\nRestart the VPN client.",
        ingested_by="admin-1",
    )
    defaults.update(overrides)
    return IngestKnowledgeDocumentCommand(**defaults)


def test_ingest_pipeline_produces_an_active_document_with_chunks() -> None:
    service, document_repository, outbox_repository, _, _ = _build_service()

    view = service.ingest(_command())

    assert view.ingestion_status.name == "ACTIVE"
    assert view.chunk_count >= 1
    stored_chunks = document_repository.find_chunks(view.document_id)
    assert all(chunk.embedding_ref is not None for chunk in stored_chunks)

    published_types = {r.event_type for r in outbox_repository.recorded()}
    assert "knowledge.document.indexed.v1" in published_types


def test_reingesting_the_identical_natural_key_and_content_is_an_idempotent_replay() -> None:
    """09-concurrency-and-idempotency §"Document Reingestion": "相同 document version
    重复导入是幂等成功" — an identical (source_system, external_id, version, content)
    retry replays the already-ingested document rather than erroring.
    """
    service, document_repository, outbox_repository, *_ = _build_service()
    first = service.ingest(_command())

    replay = service.ingest(_command())

    assert replay.document_id == first.document_id
    assert replay.chunk_count == first.chunk_count
    # No second pipeline run: still exactly one ingest_document outbox event.
    published_types = [r.event_type for r in outbox_repository.recorded()]
    assert published_types.count("knowledge.document.indexed.v1") == 1


def test_reingesting_the_same_natural_key_with_different_content_is_rejected() -> None:
    """A different content_hash under the same (source_system, external_id, version)
    is a genuine identity conflict, not an idempotent retry.
    """
    service, *_ = _build_service()
    service.ingest(_command())

    with pytest.raises(DocumentAlreadyIngestedException):
        service.ingest(_command(raw_content="a completely different document body"))


def test_whitespace_only_content_fails_instead_of_activating_an_empty_document() -> None:
    """10-failure-handling §"Poison Document": "内容为空... document 状态进入 FAILED...
    不生成 chunks / embeddings" — raw_content that passes the wire's min_length=1 check
    but parses to zero chunks must not silently produce an ACTIVE document with no
    retrievable content.
    """
    service, document_repository, *_ = _build_service()

    with pytest.raises(DocumentIngestionFailedException):
        service.ingest(_command(raw_content="   \n\n   "))

    stored = document_repository.find_by_natural_key("confluence", "KB-100", 1)
    assert stored is not None
    assert stored.ingestion_status.name == "FAILED"


def test_a_different_version_is_a_new_document() -> None:
    service, *_ = _build_service()
    service.ingest(_command())

    second = service.ingest(_command(version=2))
    assert second.version == 2


def test_secrets_in_raw_content_are_redacted_before_chunk_storage() -> None:
    service, document_repository, *_ = _build_service()
    view = service.ingest(_command(raw_content="Contact: user@example.com\napi_key: abcd1234efgh5678"))

    chunks = document_repository.find_chunks(view.document_id)
    joined = " ".join(c.content for c in chunks)
    assert "user@example.com" not in joined
    assert "abcd1234efgh5678" not in joined


def test_extract_graph_creates_nodes_and_edges_from_markers() -> None:
    service, _, outbox_repository, graph_node_repository, graph_edge_repository = _build_service()

    view = service.ingest(_command(
        raw_content="SERVICE: vpn-auth is affected by SYMPTOM: mfa-loop-after-reset.", extract_graph=True,
    ))

    assert view.ingestion_status.name == "ACTIVE"
    published_types = {r.event_type for r in outbox_repository.recorded()}
    assert "knowledge.graph.updated.v1" in published_types


class _FailingEntityExtractor:
    def extract(self, redacted_text: str, evidence_refs):
        raise RuntimeError("entity extraction backend unavailable")


def test_graph_extraction_failure_blocks_activation_instead_of_leaking_an_active_document() -> None:
    """08-transaction-and-outbox §"Graph Upsert Transaction": "如果 graph upsert
    失败：document ingestion 不进入 ACTIVE". 10-failure-handling §"Graph Failure":
    "entity extraction 失败时，document / candidate 不进入 searchable active 状态."
    """
    document_repository = InMemoryKnowledgeDocumentRepository()
    outbox_repository = InMemoryOutboxRepository()
    service = IngestKnowledgeDocumentService(
        document_repository, SimpleDocumentParserAdapter(), RegexRedactionPolicyAdapter(), DeterministicHashEmbeddingProvider(),
        InMemoryEmbeddingRepository(), _FailingEntityExtractor(), InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository(),
        outbox_repository, InMemoryAuditRecordRepository(), SystemClockAdapter(),
    )

    with pytest.raises(DocumentIngestionFailedException):
        service.ingest(_command(
            raw_content="SERVICE: vpn-auth is affected by SYMPTOM: mfa-loop-after-reset.", extract_graph=True,
        ))

    stored = document_repository.find_by_natural_key("confluence", "KB-100", 1)
    assert stored is not None
    assert stored.ingestion_status.name == "FAILED"
    published_types = {r.event_type for r in outbox_repository.recorded()}
    assert "knowledge.document.indexed.v1" not in published_types
    assert "knowledge.graph.updated.v1" not in published_types
