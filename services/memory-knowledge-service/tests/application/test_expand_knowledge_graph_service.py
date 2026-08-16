from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import ExpandKnowledgeGraphCommand
from memoryknowledge.application.exceptions import GraphTraversalDepthExceededException
from memoryknowledge.application.services.expand_knowledge_graph import ExpandKnowledgeGraphService
from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.values import AccessScope, SourceRef
from memoryknowledge.infrastructure.authorization import StaticAuthorizationPolicyAdapter
from memoryknowledge.infrastructure.persistence.in_memory import InMemoryGraphEdgeRepository, InMemoryGraphNodeRepository

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_chain(node_repository: InMemoryGraphNodeRepository, edge_repository: InMemoryGraphEdgeRepository, length: int) -> list[GraphNodeId]:
    nodes = []
    for i in range(length):
        node = GraphNode.create(
            GraphNodeId.new_id(), GraphNodeType.SYMPTOM, f"symptom:{i}", f"symptom-{i}", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
        )
        node_repository.save(node)
        nodes.append(node.node_id)
    for i in range(length - 1):
        edge = GraphEdge.create(
            GraphEdgeId.new_id(), GraphEdgeType.SIMILAR_TO, nodes[i], nodes[i + 1], 0.9, (SourceRef("ticket", "T-1"),), f"hash-{i}", _now(),
        )
        edge_repository.save(edge)
    return nodes


def test_expand_returns_bounded_paths_from_seed() -> None:
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    nodes = _build_chain(node_repository, edge_repository, length=3)
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter())

    result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))

    assert not result.truncated
    assert len(result.paths) == 2  # depth 1 and depth 2 hops from the seed


def test_expand_does_not_traverse_beyond_max_depth() -> None:
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    nodes = _build_chain(node_repository, edge_repository, length=5)
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter())

    result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))

    max_hops = max(len(path.edge_ids) for path in result.paths)
    assert max_hops <= 2


def test_depth_beyond_default_requires_explicit_deep_traversal_flag() -> None:
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    nodes = _build_chain(node_repository, edge_repository, length=2)
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter())

    with pytest.raises(GraphTraversalDepthExceededException):
        service.expand(ExpandKnowledgeGraphCommand(
            seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
            requester_type="agent", requester_id="agent-1", max_depth=3, allow_deep_traversal=False,
        ))

    # Explicit escape hatch is allowed.
    result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=3, allow_deep_traversal=True,
    ))
    assert len(result.paths) >= 1


def test_expand_skips_nodes_the_requester_is_not_authorized_for() -> None:
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    nodes = _build_chain(node_repository, edge_repository, length=2)

    class DenyAllAuthorization:
        def is_retrieval_authorized(self, access_scope, classification) -> bool:
            return False

    service = ExpandKnowledgeGraphService(node_repository, edge_repository, DenyAllAuthorization())
    result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))

    assert result.paths == ()
