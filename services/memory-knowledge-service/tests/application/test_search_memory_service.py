from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import SearchMemoryCommand
from memoryknowledge.application.services.expand_knowledge_graph import ExpandKnowledgeGraphService
from memoryknowledge.application.services.search_memory import SearchMemoryService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.domain.ids import CorrelationId
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.values import AccessScope, SourceRef
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
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import MemoryId, MemoryVersionId
from memoryknowledge.domain.values import RedactionReport

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_service(memory_repository=None, authorization_port=None, graph_node_repository=None):
    memory_repository = memory_repository or InMemoryMemoryRepository()
    graph_node_repository = graph_node_repository or InMemoryGraphNodeRepository()
    graph_edge_repository = InMemoryGraphEdgeRepository()
    authorization_port = authorization_port or StaticAuthorizationPolicyAdapter()
    expand_service = ExpandKnowledgeGraphService(graph_node_repository, graph_edge_repository, authorization_port, MemoryTelemetry())
    return (
        SearchMemoryService(
            memory_repository, InMemoryKnowledgeDocumentRepository(), graph_node_repository, InMemoryRetrievalLogRepository(),
            authorization_port, RegexRedactionPolicyAdapter(), SimpleGraphRerankerAdapter(), expand_service, SystemClockAdapter(), MemoryTelemetry(),
        ),
        memory_repository, graph_node_repository,
    )


def _seed_active_version(
    memory_repository: InMemoryMemoryRepository, *, summary: str, memory_type: MemoryType = MemoryType.EPISODIC,
    classification: str = "INTERNAL", source_trust_score: float = 0.7,
) -> MemoryVersion:
    memory = Memory.create(MemoryId.new_id(), memory_type, _now(), classification=classification)
    memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        MemoryVersionId.new_id(), memory.memory_id, 1, summary, summary, (SourceRef("ticket", "T-1"),),
        RedactionReport(), 0.8, source_trust_score, f"hash-{uuid.uuid4()}", "agent-1", _now(),
    )
    return memory_repository.save_version(version, expected_status=None)


def test_search_returns_matching_result_with_provenance_and_writes_retrieval_log() -> None:
    service, memory_repository, _ = _build_service()
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

    service, memory_repository, _ = _build_service(authorization_port=DenyAllAuthorization())
    _seed_active_version(memory_repository, summary="vpn login fails after mfa reset")

    command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="RESTRICTED"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    assert result.results == ()


def test_search_applies_each_memorys_own_real_classification_not_a_fixed_placeholder() -> None:
    """SPEC-MK-025 02-business-invariants: "高敏 classification 的 memory 默认不可跨
    queue / role 检索"; 11-security §"检索前必须计算 access scope，并应用到：Memory
    classification." Two memories with *different* classification must be filtered
    differently by the same requester, proving this is real per-object data now, not
    the single fixed constant every result used to share.
    """
    service, memory_repository, _ = _build_service()
    _seed_active_version(memory_repository, summary="vpn login fails after mfa reset", classification="INTERNAL")
    _seed_active_version(memory_repository, summary="vpn login fails after admin override", classification="RESTRICTED")

    agent_command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    agent_result = service.search(agent_command)
    assert len(agent_result.results) == 1
    assert "override" not in agent_result.results[0].snippet

    admin_command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="admin-agent-1",
        access_scope=AccessScope(tenant="acme", role="admin", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    admin_result = service.search(admin_command)
    assert len(admin_result.results) == 2


def test_search_expands_the_graph_from_a_memory_seed_and_attaches_paths() -> None:
    """UC-02 step 4 "对 seed results 做 bounded graph expansion"; step 6 "返回 ...
    graph paths"."""
    from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType
    from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId
    from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode

    graph_node_repository = InMemoryGraphNodeRepository()
    service, memory_repository, _ = _build_service(graph_node_repository=graph_node_repository)
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now())
    memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        MemoryVersionId.new_id(), memory.memory_id, 1, "vpn login fails after mfa reset", "vpn login fails after mfa reset",
        (SourceRef("ticket", "T-1"),), RedactionReport(), 0.8, 0.7, "hash-graph-1", "agent-1", _now(),
    )
    memory_repository.save_version(version, expected_status=None)

    version_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.MEMORY_VERSION, f"memory_version:{version.memory_version_id}", "vpn mfa loop",
        "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    graph_node_repository.save(version_node)
    symptom_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.SYMPTOM, "symptom:mfa-loop", "mfa loop", "INTERNAL",
        (SourceRef("ticket", "T-1"),), _now(),
    )
    graph_node_repository.save(symptom_node)
    graph_edge_repository = InMemoryGraphEdgeRepository()
    edge = GraphEdge.create(
        GraphEdgeId.new_id(), GraphEdgeType.DERIVED_FROM, version_node.node_id, symptom_node.node_id, 0.9,
        (SourceRef("ticket", "T-1"),), "edge-hash-1", _now(),
    )
    graph_edge_repository.save(edge)
    # Rebuild the service so its ExpandKnowledgeGraphService shares this same edge repository.
    authorization_port = StaticAuthorizationPolicyAdapter()
    expand_service = ExpandKnowledgeGraphService(graph_node_repository, graph_edge_repository, authorization_port, MemoryTelemetry())
    service = SearchMemoryService(
        memory_repository, InMemoryKnowledgeDocumentRepository(), graph_node_repository, InMemoryRetrievalLogRepository(),
        authorization_port, RegexRedactionPolicyAdapter(), SimpleGraphRerankerAdapter(), expand_service, SystemClockAdapter(), MemoryTelemetry(),
    )

    command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    assert not result.graph_degraded
    [item] = result.results
    assert len(item.graph_paths) == 1
    assert item.graph_paths[0].explanation.endswith("via DERIVED_FROM")


def test_search_degrades_gracefully_when_repository_fails_instead_of_fabricating_evidence() -> None:
    class BrokenMemoryRepository(InMemoryMemoryRepository):
        def find_active_versions_by_type(self, memory_type_names, limit):
            raise RuntimeError("database unavailable")

    service, _, _ = _build_service(memory_repository=BrokenMemoryRepository())

    command = SearchMemoryCommand(
        query="vpn login fails", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    assert result.degraded is True
    assert result.degraded_reason == "REPOSITORY_UNAVAILABLE"
    assert result.results == ()


def test_search_query_is_redacted_before_hashing() -> None:
    """UC-02 step 2 "对 query 做 secret 检测和 normalization" — a query carrying a
    pasted secret must not reach the retrieval log's query_hash unredacted.
    """
    import hashlib

    retrieval_log_repository = InMemoryRetrievalLogRepository()
    graph_node_repository = InMemoryGraphNodeRepository()
    authorization_port = StaticAuthorizationPolicyAdapter()
    expand_service = ExpandKnowledgeGraphService(graph_node_repository, InMemoryGraphEdgeRepository(), authorization_port, MemoryTelemetry())
    service = SearchMemoryService(
        InMemoryMemoryRepository(), InMemoryKnowledgeDocumentRepository(), graph_node_repository, retrieval_log_repository,
        authorization_port, RegexRedactionPolicyAdapter(), SimpleGraphRerankerAdapter(), expand_service, SystemClockAdapter(), MemoryTelemetry(),
    )
    raw_query = "api_key: abcd1234efgh5678"
    command = SearchMemoryCommand(
        query=raw_query, requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )

    service.search(command)

    [log] = retrieval_log_repository.find_recent(limit=1)
    redacted_query, _report = RegexRedactionPolicyAdapter().redact(raw_query)
    assert redacted_query != raw_query
    assert log.query_hash == hashlib.sha256(redacted_query.encode()).hexdigest()
    assert log.query_hash != hashlib.sha256(raw_query.encode()).hexdigest()


def test_search_ranks_a_higher_source_trust_result_above_an_otherwise_identical_lower_trust_one() -> None:
    """SPEC-MK-031 14-testing-strategy §"Retrieval Quality Tests": "source trust 影响
    排序." 02-business-invariants §"检索不变量": "Retrieval score 不能只依赖 embedding
    similarity" — RetrievalScore.combined already weights trust at 0.15; this proves
    that weighting actually reaches SearchMemoryService's own result ordering, not
    just the domain-level score object in isolation.
    """
    memory_repository = InMemoryMemoryRepository()
    low_trust = _seed_active_version(memory_repository, summary="vpn login fails after mfa reset", source_trust_score=0.1)
    high_trust = _seed_active_version(memory_repository, summary="vpn login fails after mfa reset", source_trust_score=0.9)
    service, _, _ = _build_service(memory_repository=memory_repository)

    command = SearchMemoryCommand(
        query="vpn login fails after mfa reset", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    ranked_ids = [item.source_id for item in result.results]
    assert ranked_ids.index(str(high_trust.memory_id)) < ranked_ids.index(str(low_trust.memory_id))


def test_search_excludes_deprecated_and_deleted_memory_versions() -> None:
    """SPEC-MK-031 14-testing-strategy §"Retrieval Quality Tests": "expired/deprecated
    memory 不返回" — 02-business-invariants §"检索不变量" already names ACTIVE as the only
    default-retrievable status; MemoryRepository.find_active_versions_by_type() is
    where that's actually enforced against a repository, not just documented on the
    enum.
    """
    memory_repository = InMemoryMemoryRepository()
    active = _seed_active_version(memory_repository, summary="printer offline reseat network cable")
    deprecated_source = _seed_active_version(memory_repository, summary="printer offline reseat network cable")
    memory_repository.save_version(deprecated_source.deprecate(), expected_status=deprecated_source.status)
    deleted_source = _seed_active_version(memory_repository, summary="printer offline reseat network cable")
    memory_repository.save_version(deleted_source.delete(), expected_status=deleted_source.status)
    service, _, _ = _build_service(memory_repository=memory_repository)

    command = SearchMemoryCommand(
        query="printer offline reseat network cable", requester_type="agent", requester_id="knowledge-agent-1",
        access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"), correlation_id=CorrelationId.new_id(),
    )
    result = service.search(command)

    returned_ids = {item.source_id for item in result.results}
    assert str(active.memory_id) in returned_ids
    assert str(deprecated_source.memory_id) not in returned_ids
    assert str(deleted_source.memory_id) not in returned_ids
