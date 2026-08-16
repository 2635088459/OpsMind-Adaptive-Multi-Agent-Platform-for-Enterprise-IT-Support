from __future__ import annotations

import pytest

from memoryknowledge.application.commands import IngestKnowledgeDocumentCommand
from memoryknowledge.application.exceptions import DocumentAlreadyIngestedException
from memoryknowledge.application.services.ingest_document import IngestKnowledgeDocumentService
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.document_parser import SimpleDocumentParserAdapter
from memoryknowledge.infrastructure.embedding.embedding_provider import DeterministicHashEmbeddingProvider
from memoryknowledge.infrastructure.graph.entity_extractor import MarkerBasedEntityExtractorAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
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
        outbox_repository, SystemClockAdapter(),
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


def test_reingesting_the_same_natural_key_is_rejected() -> None:
    service, *_ = _build_service()
    service.ingest(_command())

    with pytest.raises(DocumentAlreadyIngestedException):
        service.ingest(_command())


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
