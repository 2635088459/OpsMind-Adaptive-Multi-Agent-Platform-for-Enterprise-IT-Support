from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import ExpandKnowledgeGraphCommand
from memoryknowledge.application.exceptions import GraphTraversalDepthExceededException
from memoryknowledge.application.services.expand_knowledge_graph import ExpandKnowledgeGraphService
from memoryknowledge.application.telemetry import MemoryTelemetry
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
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter(), MemoryTelemetry())

    result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))

    assert not result.truncated
    assert len(result.paths) == 2  # depth 1 and depth 2 hops from the seed


def test_expand_does_not_traverse_beyond_max_depth() -> None:
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    nodes = _build_chain(node_repository, edge_repository, length=5)
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter(), MemoryTelemetry())

    result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))

    max_hops = max(len(path.edge_ids) for path in result.paths)
    assert max_hops <= 2


def test_depth_beyond_default_requires_explicit_deep_traversal_flag() -> None:
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    nodes = _build_chain(node_repository, edge_repository, length=2)
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter(), MemoryTelemetry())

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

    service = ExpandKnowledgeGraphService(node_repository, edge_repository, DenyAllAuthorization(), MemoryTelemetry())
    result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(nodes[0],), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))

    assert result.paths == ()


def test_expand_hides_owned_by_edges_from_a_non_restricted_role_regardless_of_classification() -> None:
    """SPEC-MK-027 11-security §"Graph Security": "OWNED_BY / POLICY_RULE 等组织关系
    默认只对 authorized role 返回" — a floor independent of classification: both nodes
    below are "INTERNAL" (normally visible to any role), but the OWNED_BY edge
    connecting them must still be hidden from a plain "agent" role.
    """
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    service_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.SERVICE, "service:vpn-auth", "vpn-auth", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    owner_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.OWNER, "owner:platform-team", "platform-team", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    node_repository.save(service_node)
    node_repository.save(owner_node)
    edge_repository.save(GraphEdge.create(
        GraphEdgeId.new_id(), GraphEdgeType.OWNED_BY, service_node.node_id, owner_node.node_id, 0.9,
        (SourceRef("ticket", "T-1"),), "owned-by-hash-1", _now(),
    ))
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter(), MemoryTelemetry())

    agent_result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(service_node.node_id,), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))
    assert agent_result.paths == ()

    admin_result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(service_node.node_id,), access_scope=AccessScope(tenant="acme", role="admin", classification="INTERNAL"),
        requester_type="admin", requester_id="admin-1", max_depth=2,
    ))
    assert len(admin_result.paths) == 1


def test_expand_hides_policy_rule_nodes_from_a_non_restricted_role_regardless_of_classification() -> None:
    node_repository, edge_repository = InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository()
    action_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.ACTION, "action:reset-mfa", "reset-mfa", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    policy_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.POLICY_RULE, "policy:mfa-reset-requires-approval", "mfa-reset-requires-approval",
        "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    node_repository.save(action_node)
    node_repository.save(policy_node)
    edge_repository.save(GraphEdge.create(
        GraphEdgeId.new_id(), GraphEdgeType.SUPPORTED_BY, action_node.node_id, policy_node.node_id, 0.9,
        (SourceRef("ticket", "T-1"),), "policy-edge-hash-1", _now(),
    ))
    service = ExpandKnowledgeGraphService(node_repository, edge_repository, StaticAuthorizationPolicyAdapter(), MemoryTelemetry())

    agent_result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(action_node.node_id,), access_scope=AccessScope(tenant="acme", role="agent", classification="INTERNAL"),
        requester_type="agent", requester_id="agent-1", max_depth=2,
    ))
    assert agent_result.paths == ()

    admin_result = service.expand(ExpandKnowledgeGraphCommand(
        seed_node_ids=(action_node.node_id,), access_scope=AccessScope(tenant="acme", role="admin", classification="INTERNAL"),
        requester_type="admin", requester_id="admin-1", max_depth=2,
    ))
    assert len(admin_result.paths) == 1
