"""SearchMemoryService's real-semantic (pgvector) path — exercised with a fake
EmbeddingProvider + fake EmbeddingRepository so no OpenAI or Postgres is
touched. The keyword path is deliberately unchanged; these only cover the
additive vector branch container.py wires when Settings.embedding_provider=
"openai".
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import SearchMemoryCommand
from memoryknowledge.application.records import ChunkSimilarityHit
from memoryknowledge.application.services.expand_knowledge_graph import ExpandKnowledgeGraphService
from memoryknowledge.application.services.search_memory import SearchMemoryService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.domain.enums import DocumentIngestionStatus, MemoryType
from memoryknowledge.domain.ids import CorrelationId, KnowledgeDocumentId
from memoryknowledge.domain.knowledge_document import KnowledgeDocument
from memoryknowledge.domain.values import AccessScope, EmbeddingRef
from memoryknowledge.infrastructure.authorization import StaticAuthorizationPolicyAdapter
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryGraphEdgeRepository,
    InMemoryGraphNodeRepository,
    InMemoryKnowledgeDocumentRepository,
    InMemoryMemoryRepository,
    InMemoryRetrievalLogRepository,
)
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter
from memoryknowledge.infrastructure.retrieval.reranker import SimpleGraphRerankerAdapter

pytestmark = pytest.mark.unit

_MODEL = "text-embedding-3-small"


class FakeEmbeddingProvider:
    def __init__(self, *, fail: bool = False) -> None:
        self._fail = fail
        self.calls: list[str] = []

    def embed(self, text: str):
        self.calls.append(text)
        if self._fail:
            raise RuntimeError("embeddings provider is down")
        return EmbeddingRef(provider="openai", model=_MODEL, dimensions=3, vector_id=str(uuid.uuid4())), (0.1, 0.2, 0.3)


class FakeEmbeddingRepository:
    def __init__(self, hits: list[ChunkSimilarityHit]) -> None:
        self._hits = hits
        self.queried_with: tuple | None = None

    def save(self, embedding_ref, vector) -> None:  # pragma: no cover - unused here
        pass

    def find(self, vector_id):  # pragma: no cover - unused here
        return None

    def search_similar_chunks(self, query_vector, provider, model, limit):
        self.queried_with = (query_vector, provider, model, limit)
        return list(self._hits)


def _now() -> datetime:
    return datetime.now(UTC)


def _seed_active_document(repo: InMemoryKnowledgeDocumentRepository, *, acl=(), classification="INTERNAL") -> KnowledgeDocument:
    document = KnowledgeDocument(
        document_id=KnowledgeDocumentId.new_id(), source_system="manual", external_id=f"kb-{uuid.uuid4()}",
        title="Reset MFA", document_type="runbook", acl=tuple(acl), version=1,
        ingestion_status=DocumentIngestionStatus.ACTIVE, content_hash="h", created_at=_now(), classification=classification,
    )
    # bypass the state-machine save guard: the doc is already ACTIVE for the test
    repo._by_id[document.document_id] = document  # noqa: SLF001 - test seam
    return document


def _service(document_repo, embedding_provider, embedding_repository):
    graph_node_repository = InMemoryGraphNodeRepository()
    expand = ExpandKnowledgeGraphService(
        graph_node_repository, InMemoryGraphEdgeRepository(), StaticAuthorizationPolicyAdapter(), MemoryTelemetry()
    )
    return SearchMemoryService(
        InMemoryMemoryRepository(), document_repo, graph_node_repository, InMemoryRetrievalLogRepository(),
        StaticAuthorizationPolicyAdapter(), RegexRedactionPolicyAdapter(), SimpleGraphRerankerAdapter(), expand,
        SystemClockAdapter(), MemoryTelemetry(),
        embedding_provider=embedding_provider, embedding_repository=embedding_repository,
    )


def _command(query: str = "how do I reset mfa") -> SearchMemoryCommand:
    return SearchMemoryCommand(
        query=query, requester_type="agent", requester_id="agent-1",
        access_scope=AccessScope(tenant="default", role="EMPLOYEE", classification="INTERNAL"),
        correlation_id=CorrelationId.new_id(), memory_types=(MemoryType.EPISODIC,), max_results=10,
        include_graph_paths=False,
    )


def test_semantic_hit_becomes_a_document_chunk_result() -> None:
    doc_repo = InMemoryKnowledgeDocumentRepository()
    document = _seed_active_document(doc_repo)
    provider = FakeEmbeddingProvider()
    repo = FakeEmbeddingRepository([
        ChunkSimilarityHit(
            chunk_id="chunk-1", document_id=str(document.document_id), document_version=1,
            content="Open the admin console and clear the user's MFA enrollment.", distance=0.15,
        )
    ])
    view = _service(doc_repo, provider, repo).search(_command())

    assert provider.calls == ["how do I reset mfa"]
    assert repo.queried_with[1:] == ("openai", _MODEL, max(10 * 4, 20))
    chunk_results = [r for r in view.results if r.result_type == "DOCUMENT_CHUNK"]
    assert len(chunk_results) == 1
    assert chunk_results[0].provenance.source_ref == "chunk-1"
    assert chunk_results[0].score == pytest.approx(0.85)
    assert view.degraded is False


def test_orthogonal_hit_scores_zero_and_is_dropped() -> None:
    doc_repo = InMemoryKnowledgeDocumentRepository()
    document = _seed_active_document(doc_repo)
    repo = FakeEmbeddingRepository([
        ChunkSimilarityHit(chunk_id="c", document_id=str(document.document_id), document_version=1, content="unrelated", distance=1.4)
    ])
    view = _service(doc_repo, FakeEmbeddingProvider(), repo).search(_command())

    assert [r for r in view.results if r.result_type == "DOCUMENT_CHUNK"] == []


def test_acl_denied_semantic_hit_is_filtered_out() -> None:
    doc_repo = InMemoryKnowledgeDocumentRepository()
    document = _seed_active_document(doc_repo, acl=("SUPPORT_AGENT",))  # requester role is EMPLOYEE
    repo = FakeEmbeddingRepository([
        ChunkSimilarityHit(chunk_id="c", document_id=str(document.document_id), document_version=1, content="secret runbook", distance=0.1)
    ])
    view = _service(doc_repo, FakeEmbeddingProvider(), repo).search(_command())

    assert [r for r in view.results if r.result_type == "DOCUMENT_CHUNK"] == []


def test_embedding_failure_degrades_without_raising() -> None:
    doc_repo = InMemoryKnowledgeDocumentRepository()
    view = _service(doc_repo, FakeEmbeddingProvider(fail=True), FakeEmbeddingRepository([])).search(_command())

    assert view.degraded is True
    assert view.degraded_reason == "EMBEDDING_UNAVAILABLE"


def test_no_embedding_wiring_leaves_search_keyword_only() -> None:
    doc_repo = InMemoryKnowledgeDocumentRepository()
    graph_node_repository = InMemoryGraphNodeRepository()
    expand = ExpandKnowledgeGraphService(
        graph_node_repository, InMemoryGraphEdgeRepository(), StaticAuthorizationPolicyAdapter(), MemoryTelemetry()
    )
    service = SearchMemoryService(
        InMemoryMemoryRepository(), doc_repo, graph_node_repository, InMemoryRetrievalLogRepository(),
        StaticAuthorizationPolicyAdapter(), RegexRedactionPolicyAdapter(), SimpleGraphRerankerAdapter(), expand,
        SystemClockAdapter(), MemoryTelemetry(),
    )
    view = service.search(_command())

    assert view.degraded is False
    assert view.results == ()
