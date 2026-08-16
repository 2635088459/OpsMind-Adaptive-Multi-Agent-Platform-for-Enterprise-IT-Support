from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import DeleteMemoryCommand, DeprecateMemoryCommand
from memoryknowledge.application.exceptions import DeletionNotAuthorizedException, MemoryNotFoundException
from memoryknowledge.application.services.execute_retention import ExecuteRetentionService
from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType, MemoryType
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId, IdempotencyKey, MemoryId, MemoryVersionId
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.values import RedactionReport, SourceRef
from memoryknowledge.infrastructure.authorization import StaticAuthorizationPolicyAdapter
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryCommandIdempotencyRepository,
    InMemoryGraphEdgeRepository,
    InMemoryGraphNodeRepository,
    InMemoryMemoryRepository,
    InMemoryOutboxRepository,
)

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_service():
    memory_repository = InMemoryMemoryRepository()
    graph_node_repository = InMemoryGraphNodeRepository()
    graph_edge_repository = InMemoryGraphEdgeRepository()
    outbox_repository = InMemoryOutboxRepository()
    service = ExecuteRetentionService(
        memory_repository, graph_node_repository, graph_edge_repository, StaticAuthorizationPolicyAdapter(),
        InMemoryCommandIdempotencyRepository(), outbox_repository, SystemClockAdapter(),
    )
    return service, memory_repository, graph_node_repository, graph_edge_repository, outbox_repository


def _seed_published_memory(memory_repository: InMemoryMemoryRepository) -> MemoryId:
    memory = Memory.create(MemoryId.new_id(), MemoryType.EPISODIC, _now())
    memory_repository.save_memory(memory)
    version = MemoryVersion.create_active(
        MemoryVersionId.new_id(), memory.memory_id, 1, "content", "summary", (SourceRef("ticket", "T-1"),),
        RedactionReport(), 0.8, 0.8, "hash-1", "agent-1", _now(),
    )
    memory_repository.save_version(version, expected_status=None)
    return memory.memory_id


def test_deprecate_moves_active_version_to_deprecated_and_out_of_default_retrieval() -> None:
    service, memory_repository, *_ = _build_service()
    memory_id = _seed_published_memory(memory_repository)

    view = service.deprecate(DeprecateMemoryCommand(memory_id=memory_id, actor_id="admin-1", idempotency_key=IdempotencyKey("dep-1")))

    assert view.status.name == "DEPRECATED"
    assert memory_repository.find_active_version(memory_id) is None


def test_deprecate_unknown_memory_raises_not_found() -> None:
    service, *_ = _build_service()
    with pytest.raises(MemoryNotFoundException):
        service.deprecate(DeprecateMemoryCommand(memory_id=MemoryId.new_id(), actor_id="admin-1", idempotency_key=IdempotencyKey("dep-x")))


def test_delete_marks_versions_deleted_and_tombstones_linked_graph_entities() -> None:
    service, memory_repository, graph_node_repository, graph_edge_repository, outbox_repository = _build_service()
    memory_id = _seed_published_memory(memory_repository)

    memory_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.MEMORY, f"memory:{memory_id}", "memory node", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    graph_node_repository.save(memory_node)
    other_node = GraphNode.create(
        GraphNodeId.new_id(), GraphNodeType.SYMPTOM, "symptom:mfa-loop", "mfa loop", "INTERNAL", (SourceRef("ticket", "T-1"),), _now(),
    )
    graph_node_repository.save(other_node)
    edge = GraphEdge.create(
        GraphEdgeId.new_id(), GraphEdgeType.DERIVED_FROM, memory_node.node_id, other_node.node_id, 0.9,
        (SourceRef("ticket", "T-1"),), "edge-hash-1", _now(),
    )
    graph_edge_repository.save(edge)

    report = service.delete(DeleteMemoryCommand(memory_id=memory_id, reason="pii leaked", actor_id="admin-1", idempotency_key=IdempotencyKey("del-1")))

    assert report.versions_deleted == 1
    assert report.graph_nodes_tombstoned == 1
    assert report.graph_edges_tombstoned == 1
    assert memory_repository.find_active_version(memory_id) is None
    assert graph_node_repository.find_by_id(memory_node.node_id).status.name == "TOMBSTONED"
    assert graph_edge_repository.find_by_id(edge.edge_id).status.name == "TOMBSTONED"
    assert any(r.event_type == "memory.deleted.v1" for r in outbox_repository.recorded())


def test_delete_requires_authorization() -> None:
    memory_repository = InMemoryMemoryRepository()
    memory_id = _seed_published_memory(memory_repository)

    class DenyAllAuthorization:
        def is_deletion_authorized(self, actor_id, memory_id) -> bool:
            return False

    service = ExecuteRetentionService(
        memory_repository, InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository(), DenyAllAuthorization(),
        InMemoryCommandIdempotencyRepository(), InMemoryOutboxRepository(), SystemClockAdapter(),
    )

    with pytest.raises(DeletionNotAuthorizedException):
        service.delete(DeleteMemoryCommand(memory_id=memory_id, reason="pii leaked", actor_id="", idempotency_key=IdempotencyKey("del-2")))


def test_delete_is_idempotent_under_the_same_key() -> None:
    service, memory_repository, *_ = _build_service()
    memory_id = _seed_published_memory(memory_repository)

    first = service.delete(DeleteMemoryCommand(memory_id=memory_id, reason="x", actor_id="admin-1", idempotency_key=IdempotencyKey("del-dup")))
    second = service.delete(DeleteMemoryCommand(memory_id=memory_id, reason="x", actor_id="admin-1", idempotency_key=IdempotencyKey("del-dup")))

    assert first.versions_deleted == second.versions_deleted
