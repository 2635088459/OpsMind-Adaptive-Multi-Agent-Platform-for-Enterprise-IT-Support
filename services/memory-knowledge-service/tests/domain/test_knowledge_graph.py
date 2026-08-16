from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType
from memoryknowledge.domain.exceptions import GraphEdgeMissingEvidenceException, InvalidGraphNodeTransitionException
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.values import SourceRef

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def test_create_node_defaults_to_visible() -> None:
    node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.SERVICE, "service:vpn-auth", "VPN Auth", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    assert node.status.name == "VISIBLE"


def test_node_hide_show_and_one_way_tombstone() -> None:
    node = GraphNode.create(GraphNodeId.new_id(), GraphNodeType.SERVICE, "service:vpn-auth", "VPN Auth", "INTERNAL", (SourceRef("ticket", "T-1"),), _now())

    hidden = node.hide()
    assert hidden.status.name == "HIDDEN"
    shown = hidden.show()
    assert shown.status.name == "VISIBLE"

    tombstoned = shown.tombstone()
    assert tombstoned.status.name == "TOMBSTONED"
    with pytest.raises(InvalidGraphNodeTransitionException):
        tombstoned.show()
    with pytest.raises(InvalidGraphNodeTransitionException):
        tombstoned.hide()


def test_edge_requires_evidence_refs() -> None:
    with pytest.raises(GraphEdgeMissingEvidenceException):
        GraphEdge.create(GraphEdgeId.new_id(), GraphEdgeType.RESOLVED_BY, GraphNodeId.new_id(), GraphNodeId.new_id(), 0.8, (), "hash-1", _now())


def test_edge_confidence_must_be_within_bounds() -> None:
    with pytest.raises(ValueError):
        GraphEdge.create(
            GraphEdgeId.new_id(), GraphEdgeType.RESOLVED_BY, GraphNodeId.new_id(), GraphNodeId.new_id(), 1.5,
            (SourceRef("ticket", "T-1"),), "hash-1", _now(),
        )
