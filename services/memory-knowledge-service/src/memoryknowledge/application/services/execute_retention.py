"""13-package-and-class-design §"Application Layer": ExecuteRetentionService, the sole
implementation of ExecuteRetentionUseCase. 03-state-machine §"Deletion 状态机": "删除必须
覆盖: memory content、memory versions、embeddings、document chunks、retrieval visibility
和 cache" — this spec covers memory versions + graph node/edge visibility synchronously;
see DeleteMemoryCommand's own docstring for what is deferred to phase-07.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from memoryknowledge.application.commands import DeleteMemoryCommand, DeprecateMemoryCommand
from memoryknowledge.application.exceptions import DeletionNotAuthorizedException, MemoryNotFoundException
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.ports_out import (
    AuditRecordRepository,
    AuthorizationPort,
    ClockPort,
    CommandIdempotencyRepository,
    GraphEdgeRepository,
    GraphNodeRepository,
    MemoryRepository,
    OutboxRepository,
)
from memoryknowledge.application.services.audit import AuditRecorder
from memoryknowledge.application.services.idempotency import CommandIdempotencyGuard
from memoryknowledge.application.views import DeletionReport, MemoryVersionView
from memoryknowledge.domain.enums import GraphNodeType, MemoryVersionStatus
from memoryknowledge.domain.events import MemoryDeleted
from memoryknowledge.domain.ids import MemoryId, MemoryVersionId

_DELETABLE_STATUSES = frozenset({MemoryVersionStatus.ACTIVE, MemoryVersionStatus.SUPERSEDED, MemoryVersionStatus.DEPRECATED})
_DEPRECATE_COMMAND_TYPE = "deprecate_memory"
_DELETE_COMMAND_TYPE = "delete_memory"


class ExecuteRetentionService:
    def __init__(
        self, memory_repository: MemoryRepository, graph_node_repository: GraphNodeRepository, graph_edge_repository: GraphEdgeRepository,
        authorization_port: AuthorizationPort, command_idempotency_repository: CommandIdempotencyRepository,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._memory_repository = memory_repository
        self._graph_node_repository = graph_node_repository
        self._graph_edge_repository = graph_edge_repository
        self._authorization_port = authorization_port
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def deprecate(self, command: DeprecateMemoryCommand) -> MemoryVersionView:
        """05-api-contracts: `POST .../memories/{memoryId}/deprecate`. "将 active memory
        标记为 deprecated，默认不再返回给 Agent" — 02-business-invariants §"检索不变量":
        MemoryVersionStatus.is_default_retrievable() already excludes DEPRECATED.
        """
        return self._idempotency_guard.run(
            command_type=_DEPRECATE_COMMAND_TYPE, target_id=str(command.memory_id), idempotency_key=command.idempotency_key,
            request_payload={"memory_id": str(command.memory_id), "actor_id": command.actor_id},
            execute=lambda: self._do_deprecate(command), to_dict=_version_view_to_dict, from_dict=_version_view_from_dict,
        )

    def _do_deprecate(self, command: DeprecateMemoryCommand) -> MemoryVersionView:
        version = self._memory_repository.find_active_version(command.memory_id)
        if version is None:
            raise MemoryNotFoundException(command.memory_id)
        previous_status = version.status
        version = version.deprecate()
        saved = self._memory_repository.save_version(version, previous_status)
        self._audit_recorder.record(
            audit_type="MEMORY", action="deprecate_memory", resource_type="MEMORY", resource_id=str(command.memory_id),
            outcome="SUCCESS", actor_id=command.actor_id,
        )
        return MemoryVersionView.from_domain(saved)

    def delete(self, command: DeleteMemoryCommand) -> DeletionReport:
        if not self._authorization_port.is_deletion_authorized(command.actor_id, command.memory_id):
            self._audit_recorder.record(
                audit_type="MEMORY", action="delete_memory", resource_type="MEMORY", resource_id=str(command.memory_id),
                outcome="DENIED", actor_id=command.actor_id,
            )
            raise DeletionNotAuthorizedException(command.memory_id)

        return self._idempotency_guard.run(
            command_type=_DELETE_COMMAND_TYPE, target_id=str(command.memory_id), idempotency_key=command.idempotency_key,
            request_payload={"memory_id": str(command.memory_id), "reason": command.reason, "actor_id": command.actor_id},
            execute=lambda: self._do_delete(command), to_dict=_deletion_report_to_dict, from_dict=_deletion_report_from_dict,
        )

    def _do_delete(self, command: DeleteMemoryCommand) -> DeletionReport:
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
        self._outbox_repository.append(build_outbox_record(
            MemoryDeleted(source_type="MEMORY", source_id=str(command.memory_id), occurred_at=now),
            "memory.deleted.v1", aggregate_id=str(command.memory_id), occurred_at=now,
        ))
        self._audit_recorder.record(
            audit_type="MEMORY", action="delete_memory", resource_type="MEMORY", resource_id=str(command.memory_id),
            outcome="SUCCESS", actor_id=command.actor_id, detail=f'{{"reason": "{command.reason}"}}',
        )
        return DeletionReport(command.memory_id, versions_deleted, nodes_tombstoned, edges_tombstoned)


def _version_view_to_dict(view: MemoryVersionView) -> dict:
    return {
        "memory_id": str(view.memory_id.value), "memory_version_id": str(view.memory_version_id.value), "version": view.version,
        "status": view.status.name, "summary": view.summary, "confidence_score": view.confidence_score,
        "source_trust_score": view.source_trust_score, "created_at": view.created_at.isoformat(),
    }


def _version_view_from_dict(data: dict) -> MemoryVersionView:
    return MemoryVersionView(
        memory_id=MemoryId(uuid.UUID(data["memory_id"])), memory_version_id=MemoryVersionId(uuid.UUID(data["memory_version_id"])),
        version=data["version"], status=MemoryVersionStatus[data["status"]], summary=data["summary"],
        confidence_score=data["confidence_score"], source_trust_score=data["source_trust_score"],
        created_at=datetime.fromisoformat(data["created_at"]),
    )


def _deletion_report_to_dict(report: DeletionReport) -> dict:
    return {
        "memory_id": str(report.memory_id.value), "versions_deleted": report.versions_deleted,
        "graph_nodes_tombstoned": report.graph_nodes_tombstoned, "graph_edges_tombstoned": report.graph_edges_tombstoned,
    }


def _deletion_report_from_dict(data: dict) -> DeletionReport:
    return DeletionReport(
        memory_id=MemoryId(uuid.UUID(data["memory_id"])), versions_deleted=data["versions_deleted"],
        graph_nodes_tombstoned=data["graph_nodes_tombstoned"], graph_edges_tombstoned=data["graph_edges_tombstoned"],
    )
