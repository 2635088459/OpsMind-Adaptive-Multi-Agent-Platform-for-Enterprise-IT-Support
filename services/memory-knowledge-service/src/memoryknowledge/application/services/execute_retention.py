"""13-package-and-class-design §"Application Layer": ExecuteRetentionService, the sole
implementation of ExecuteRetentionUseCase. 03-state-machine §"Deletion 状态机": "删除必须
覆盖: memory content、memory versions、embeddings、document chunks、retrieval visibility
和 cache" — this spec covers memory versions + graph node/edge visibility synchronously;
see DeleteMemoryCommand's own docstring for what is deferred to phase-07.
"""

from __future__ import annotations

from memoryknowledge.application.commands import DeleteMemoryCommand, DeprecateMemoryCommand
from memoryknowledge.application.exceptions import DeletionNotAuthorizedException, MemoryNotFoundException
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.ports_out import (
    AuthorizationPort,
    ClockPort,
    CommandIdempotencyRepository,
    GraphEdgeRepository,
    GraphNodeRepository,
    MemoryRepository,
    OutboxRepository,
)
from memoryknowledge.application.records import CommandIdempotencyRecord
from memoryknowledge.application.views import DeletionReport, MemoryVersionView
from memoryknowledge.domain.enums import GraphNodeType, MemoryVersionStatus
from memoryknowledge.domain.events import MemoryDeleted

_DELETABLE_STATUSES = frozenset({MemoryVersionStatus.ACTIVE, MemoryVersionStatus.SUPERSEDED, MemoryVersionStatus.DEPRECATED})
_COMMAND_TYPE = "delete_memory"


class ExecuteRetentionService:
    def __init__(
        self, memory_repository: MemoryRepository, graph_node_repository: GraphNodeRepository, graph_edge_repository: GraphEdgeRepository,
        authorization_port: AuthorizationPort, command_idempotency_repository: CommandIdempotencyRepository,
        outbox_repository: OutboxRepository, clock: ClockPort,
    ) -> None:
        self._memory_repository = memory_repository
        self._graph_node_repository = graph_node_repository
        self._graph_edge_repository = graph_edge_repository
        self._authorization_port = authorization_port
        self._command_idempotency_repository = command_idempotency_repository
        self._outbox_repository = outbox_repository
        self._clock = clock

    def deprecate(self, command: DeprecateMemoryCommand) -> MemoryVersionView:
        """05-api-contracts: `POST .../memories/{memoryId}/deprecate`. "将 active memory
        标记为 deprecated，默认不再返回给 Agent" — 02-business-invariants §"检索不变量":
        MemoryVersionStatus.is_default_retrievable() already excludes DEPRECATED.
        """
        version = self._memory_repository.find_active_version(command.memory_id)
        if version is None:
            raise MemoryNotFoundException(command.memory_id)
        previous_status = version.status
        version = version.deprecate()
        saved = self._memory_repository.save_version(version, previous_status)
        return MemoryVersionView.from_domain(saved)

    def delete(self, command: DeleteMemoryCommand) -> DeletionReport:
        if not self._authorization_port.is_deletion_authorized(command.actor_id, command.memory_id):
            raise DeletionNotAuthorizedException(command.memory_id)

        existing = self._command_idempotency_repository.find_by_key(command.idempotency_key)
        if existing is not None:
            versions = self._memory_repository.find_versions(command.memory_id)
            deleted = sum(1 for v in versions if v.status is MemoryVersionStatus.DELETED)
            return DeletionReport(command.memory_id, deleted, 0, 0)

        versions = self._memory_repository.find_versions(command.memory_id)
        if not versions:
            raise MemoryNotFoundException(command.memory_id)

        versions_deleted = 0
        for version in versions:
            if version.status not in _DELETABLE_STATUSES:
                continue
            previous_status = version.status
            deleted_version = version.delete()
            self._memory_repository.save_version(deleted_version, previous_status)
            versions_deleted += 1

        nodes_tombstoned = 0
        edges_tombstoned = 0
        node = self._graph_node_repository.find_by_stable_key(f"memory:{command.memory_id}", GraphNodeType.MEMORY)
        if node is not None:
            for edge in self._graph_edge_repository.find_adjacent(node.node_id, limit=500):
                self._graph_edge_repository.save(edge.tombstone())
                edges_tombstoned += 1
            self._graph_node_repository.save(node.tombstone())
            nodes_tombstoned = 1

        now = self._clock.now()
        self._command_idempotency_repository.save(
            CommandIdempotencyRecord(command.idempotency_key, _COMMAND_TYPE, str(command.memory_id), now)
        )
        self._outbox_repository.append(build_outbox_record(
            MemoryDeleted(source_type="MEMORY", source_id=str(command.memory_id), occurred_at=now),
            "memory.deleted.v1", aggregate_id=str(command.memory_id), occurred_at=now,
        ))
        return DeletionReport(command.memory_id, versions_deleted, nodes_tombstoned, edges_tombstoned)
