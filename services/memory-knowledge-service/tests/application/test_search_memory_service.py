from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import SearchMemoryCommand
from memoryknowledge.application.services.search_memory import SearchMemoryService
from memoryknowledge.domain.ids import CorrelationId
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.values import AccessScope, SourceRef
from memoryknowledge.infrastructure.authorization import StaticAuthorizationPolicyAdapter
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryKnowledgeDocumentRepository,
    InMemoryMemoryRepository,
    InMemoryRetrievalLogRepository,
)
from memoryknowledge.infrastructure.retrieval.reranker import SimpleGraphRerankerAdapter
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import MemoryId, MemoryVersionId
from memoryknowledge.domain.values import RedactionReport

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_service(memory_repository=None, authorization_port=None):
    memory_repository = memory_repository or InMemoryMemoryRepository()
    return (
        SearchMemoryService(
            memory_repository, InMemoryKnowledgeDocumentRepository(), InMemoryRetrievalLogRepository(),
            authorization_port or StaticAuthorizationPolicyAdapter(), SimpleGraphRerankerAdapter(), SystemClockAdapter(),
        ),
        memory_repository,
    )


def _seed_active_version(memory_repository: InMemoryMemoryRepository, *, summary: str, memory_type: MemoryType = MemoryType.EPISODIC) -> None:
    memory = Memory.create(MemoryId.new_id(), memory_type, _now())
    memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        MemoryVersionId.new_id(), memory.memory_id, 1, summary, summary, (SourceRef("ticket", "T-1"),),
        RedactionReport(), 0.8, 0.7, f"hash-{uuid.uuid4()}", "agent-1", _now(),
    )
    memory_repository.save_version(version, expected_status=None)


def test_search_returns_matching_result_with_provenance_and_writes_retrieval_log() -> None:
    service, memory_repository = _build_service()
    _seed_active_version(memory_repository, summary="vpn login fails after mfa reset")

    command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    assert not result.degraded
    assert len(result.results) == 1
    assert result.results[0].provenance.redacted is True
    assert result.results[0].result_type == "MEMORY"


def test_search_excludes_results_the_requester_is_not_authorized_for() -> None:
    class DenyAllAuthorization:
        def is_retrieval_authorized(self, access_scope, classification) -> bool:
            return False

    service, memory_repository = _build_service(authorization_port=DenyAllAuthorization())
    _seed_active_version(memory_repository, summary="vpn login fails after mfa reset")

    command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="RESTRICTED"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    assert result.results == ()


def test_search_degrades_gracefully_when_repository_fails_instead_of_fabricating_evidence() -> None:
    class BrokenMemoryRepository(InMemoryMemoryRepository):
        def find_active_versions_by_type(self, memory_type_names, limit):
            raise RuntimeError("database unavailable")

    service, _ = _build_service(memory_repository=BrokenMemoryRepository())

    command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    assert result.degraded is True
    assert result.results == ()
