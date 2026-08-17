"""13-package-and-class-design §"Application Layer": ExpandKnowledgeGraphService, the
sole implementation of ExpandKnowledgeGraphUseCase. 13-package-and-class-design §"类设计
原则": "Graph expander 必须是 bounded traversal，不允许 repository 返回无限邻接节点."
"""

from __future__ import annotations

import time

from opentelemetry import trace

from memoryknowledge.application.commands import ExpandKnowledgeGraphCommand
from memoryknowledge.application.exceptions import GraphTraversalDepthExceededException
from memoryknowledge.application.ports_out import AuthorizationPort, GraphEdgeRepository, GraphNodeRepository
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.application.views import GraphExpansionView
from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeStatus, GraphNodeType
from memoryknowledge.domain.ids import GraphNodeId
from memoryknowledge.domain.values import AccessScope, GraphPath

tracer = trace.get_tracer(__name__)

_DEFAULT_MAX_DEPTH = 2
_ADJACENT_SCAN_LIMIT = 25


class ExpandKnowledgeGraphService:
    def __init__(
        self, graph_node_repository: GraphNodeRepository, graph_edge_repository: GraphEdgeRepository, authorization_port: AuthorizationPort,
        telemetry: MemoryTelemetry,
    ) -> None:
        self._graph_node_repository = graph_node_repository
        self._graph_edge_repository = graph_edge_repository
        self._authorization_port = authorization_port
        self._telemetry = telemetry

    def expand(self, command: ExpandKnowledgeGraphCommand) -> GraphExpansionView:
        """02-business-invariants: "graph traversal depth MVP 默认不超过 2，除非
        admin/research API 明确提升"; "Graph expansion 不能绕过 ACL / classification
        filter." Bounded BFS: at most command.max_nodes distinct nodes visited, at most
        command.max_depth hops from any seed, VISIBLE edges/nodes only, every visited
        node re-checked against access_scope before being added to a path.
        """
        with tracer.start_as_current_span("knowledge.graph.expand"):
            return self._expand_traced(command)

    def _expand_traced(self, command: ExpandKnowledgeGraphCommand) -> GraphExpansionView:
        started_at = time.perf_counter()
        max_depth = command.max_depth
        if max_depth > _DEFAULT_MAX_DEPTH and not command.allow_deep_traversal:
            raise GraphTraversalDepthExceededException(max_depth, _DEFAULT_MAX_DEPTH)

        paths: list[GraphPath] = []
        visited: set[GraphNodeId] = set()
        truncated = False

        for seed_id in command.seed_node_ids:
            seed = self._graph_node_repository.find_by_id(seed_id)
            if seed is None or seed.status is not GraphNodeStatus.VISIBLE:
                continue
            if not self._node_authorized(command.access_scope, seed.classification, seed.node_type):
                continue
            visited.add(seed_id)

            frontier = [(seed_id, [str(seed_id)], [], 1.0)]
            depth = 0
            while frontier and depth < max_depth:
                next_frontier: list[tuple[GraphNodeId, list[str], list[str], float]] = []
                for node_id, node_path, edge_path, path_score in frontier:
                    if len(visited) >= command.max_nodes:
                        truncated = True
                        break
                    for edge in self._graph_edge_repository.find_adjacent(node_id, limit=_ADJACENT_SCAN_LIMIT):
                        if edge.status is not GraphNodeStatus.VISIBLE:
                            continue
                        # 11-security §"Graph Security": "OWNED_BY ... 等组织关系默认只
                        # 对 authorized role 返回" — checked before the edge is allowed
                        # to extend any path at all, independent of either endpoint's
                        # own classification.
                        if edge.edge_type is GraphEdgeType.OWNED_BY:
                            if not self._authorization_port.is_organizational_relation_authorized(command.access_scope):
                                continue
                        neighbor_id = edge.to_node_id if edge.from_node_id == node_id else edge.from_node_id
                        if neighbor_id in visited:
                            continue
                        neighbor = self._graph_node_repository.find_by_id(neighbor_id)
                        if neighbor is None or neighbor.status is not GraphNodeStatus.VISIBLE:
                            continue
                        if not self._node_authorized(command.access_scope, neighbor.classification, neighbor.node_type):
                            continue
                        visited.add(neighbor_id)
                        new_score = path_score * edge.confidence
                        new_node_path = node_path + [str(neighbor_id)]
                        new_edge_path = edge_path + [str(edge.edge_id)]
                        explanation = f"{seed.display_name} -> {neighbor.display_name} via {edge.edge_type.name}"
                        paths.append(GraphPath(
                            node_ids=tuple(new_node_path), edge_ids=tuple(new_edge_path),
                            path_score=new_score, explanation=explanation,
                        ))
                        next_frontier.append((neighbor_id, new_node_path, new_edge_path, new_score))
                        if len(visited) >= command.max_nodes:
                            truncated = True
                            break
                    if len(visited) >= command.max_nodes:
                        break
                frontier = next_frontier
                depth += 1

        paths.sort(key=lambda p: p.path_score, reverse=True)
        self._telemetry.record_graph_expansion_latency((time.perf_counter() - started_at) * 1000)
        self._telemetry.record_graph_path_returned(len(paths))
        return GraphExpansionView(paths=tuple(paths), truncated=truncated)

    def _node_authorized(self, access_scope: AccessScope, classification: str, node_type: GraphNodeType) -> bool:
        if not self._authorization_port.is_retrieval_authorized(access_scope, classification):
            return False
        # 11-security §"Graph Security": "OWNED_BY / POLICY_RULE 等组织关系默认只对
        # authorized role 返回" — a floor independent of classification, checked in
        # addition to it, never instead of it.
        if node_type is GraphNodeType.POLICY_RULE and not self._authorization_port.is_organizational_relation_authorized(access_scope):
            return False
        return True
