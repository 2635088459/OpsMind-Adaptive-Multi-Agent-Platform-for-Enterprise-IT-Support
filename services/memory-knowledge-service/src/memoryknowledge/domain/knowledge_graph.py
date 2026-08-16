"""01-domain-model §"KnowledgeGraph" / §"GraphNode" / §"GraphEdge". Graph is a
retrieval index and explanation layer, not a business state machine — 01-domain-model
§"领域边界": "Graph 中的边也不是最终事实本身. 它们只是带 evidence 的可解释索引."
03-state-machine §"Graph Index 状态": VISIBLE <-> HIDDEN, either -> TOMBSTONED (one-way).
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import datetime
from typing import Mapping

from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeStatus, GraphNodeType
from memoryknowledge.domain.exceptions import GraphEdgeMissingEvidenceException, InvalidGraphNodeTransitionException
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId
from memoryknowledge.domain.values import SourceRef


@dataclass(frozen=True, slots=True)
class GraphNode:
    """02-business-invariants: "stableKey + nodeType 唯一，防止同一 service / symptom 被
    重复建点" — the uniqueness check itself lives at the repository layer.
    """

    node_id: GraphNodeId
    node_type: GraphNodeType
    stable_key: str
    display_name: str
    properties: Mapping[str, str]
    classification: str
    source_refs: tuple[SourceRef, ...]
    status: GraphNodeStatus
    created_at: datetime

    @staticmethod
    def create(
        node_id: GraphNodeId, node_type: GraphNodeType, stable_key: str, display_name: str,
        classification: str, source_refs: tuple[SourceRef, ...], created_at: datetime,
        properties: Mapping[str, str] | None = None,
    ) -> "GraphNode":
        if not stable_key or not stable_key.strip():
            raise ValueError("stableKey must not be blank")
        return GraphNode(
            node_id=node_id, node_type=node_type, stable_key=stable_key, display_name=display_name,
            properties=dict(properties or {}), classification=classification, source_refs=source_refs,
            status=GraphNodeStatus.VISIBLE, created_at=created_at,
        )

    def hide(self) -> "GraphNode":
        """03-state-machine: "VISIBLE: 可参与 search expansion"; "HIDDEN: 保留但不参与默认
        检索，例如来源 document 被 deprecated."
        """
        if self.status is GraphNodeStatus.TOMBSTONED:
            raise InvalidGraphNodeTransitionException(self.status)
        return dataclasses.replace(self, status=GraphNodeStatus.HIDDEN)

    def show(self) -> "GraphNode":
        if self.status is GraphNodeStatus.TOMBSTONED:
            raise InvalidGraphNodeTransitionException(self.status)
        return dataclasses.replace(self, status=GraphNodeStatus.VISIBLE)

    def tombstone(self) -> "GraphNode":
        """03-state-machine: "TOMBSTONED: 删除或 retention 后不可恢复检索，只保留 audit 所需
        metadata" — one-way; hide()/show() both reject a node already TOMBSTONED.
        """
        return dataclasses.replace(self, status=GraphNodeStatus.TOMBSTONED)


@dataclass(frozen=True, slots=True)
class GraphEdge:
    """02-business-invariants: "fromNodeId + toNodeId + edgeType + sourceHash 唯一，防止
    同一证据重复建边" (repository-enforced); "CONFLICTS_WITH 边不能自动决定胜负，只能触发
    candidate conflict 流程" (enforced by the application layer never auto-resolving one).
    """

    edge_id: GraphEdgeId
    edge_type: GraphEdgeType
    from_node_id: GraphNodeId
    to_node_id: GraphNodeId
    confidence: float
    evidence_refs: tuple[SourceRef, ...]
    properties: Mapping[str, str]
    source_hash: str
    status: GraphNodeStatus
    created_at: datetime

    @staticmethod
    def create(
        edge_id: GraphEdgeId, edge_type: GraphEdgeType, from_node_id: GraphNodeId, to_node_id: GraphNodeId,
        confidence: float, evidence_refs: tuple[SourceRef, ...], source_hash: str, created_at: datetime,
        properties: Mapping[str, str] | None = None,
    ) -> "GraphEdge":
        if not evidence_refs:
            raise GraphEdgeMissingEvidenceException()
        if not 0.0 <= confidence <= 1.0:
            raise ValueError("confidence must be within [0, 1]")
        return GraphEdge(
            edge_id=edge_id, edge_type=edge_type, from_node_id=from_node_id, to_node_id=to_node_id,
            confidence=confidence, evidence_refs=evidence_refs, properties=dict(properties or {}),
            source_hash=source_hash, status=GraphNodeStatus.VISIBLE, created_at=created_at,
        )

    def hide(self) -> "GraphEdge":
        if self.status is GraphNodeStatus.TOMBSTONED:
            raise InvalidGraphNodeTransitionException(self.status)
        return dataclasses.replace(self, status=GraphNodeStatus.HIDDEN)

    def tombstone(self) -> "GraphEdge":
        return dataclasses.replace(self, status=GraphNodeStatus.TOMBSTONED)
