"""13-package-and-class-design §"Application Layer" analogue: RecoverMemoryOperationsService,
the sole implementation of RecoveryPort. SPEC-MK-029 10-failure-handling §"Recovery
Workers" names five bullets; this class covers three real, distinct recoverable
concerns this codebase's own architecture actually has (see each method's own
docstring for why the other two — "outbox replay" and, folded into
scan_and_recover_ingestion(), "embedding recovery" — are not separate surfaces here),
mirroring agent-runtime-service's own RecoverWorkflowService/
RecoverExpiredLeaseTasksService precedent: one service per real recoverable concern,
not one method per LLD bullet regardless of whether this codebase's own architecture
actually distinguishes them.

Every scan here is nothing more than "detect a state a healthy synchronous write path
could never leave behind, then finish (or safely terminate) whatever that write path
was doing" — never a resume-from-arbitrary-midpoint or a fabricated retry of business
logic this codebase has no record of. 10-failure-handling's own "不允许的恢复" list
("不允许在 evidence 缺失时生成 active memory") is respected by construction: nothing here
ever creates a new Memory/MemoryCandidate — only finishes an already-committed
transition (graph upsert, deletion tombstone) or marks an already-abandoned one FAILED.
"""

from __future__ import annotations

import hashlib
import logging
import uuid
from datetime import timedelta

from opentelemetry import trace

from memoryknowledge.application.ports_out import (
    AuditRecordRepository,
    ClockPort,
    GraphEdgeRepository,
    GraphNodeRepository,
    KnowledgeDocumentRepository,
    MemoryRepository,
)
from memoryknowledge.application.services.audit import AuditRecorder
from memoryknowledge.application.views import RecoveryScanReport
from memoryknowledge.domain.enums import DocumentIngestionStatus, GraphEdgeType, GraphNodeStatus, GraphNodeType, MemoryType, MemoryVersionStatus
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId, MemoryId
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_DEFAULT_BATCH_SIZE = 50
# 10-failure-handling names no concrete threshold — this codebase's own synchronous
# ingest() pipeline typically completes in well under a second, so five minutes is a
# generous, defensible floor against racing a request that is still genuinely in
# flight (mirrors RecoverExpiredLeaseTasksService's own "no LLD-specified number,
# pick a defensible one and say so" posture for its own lease-expiry scan).
_STUCK_GRACE_PERIOD = timedelta(minutes=5)
_STUCK_INGESTION_STATUSES = (
    DocumentIngestionStatus.RECEIVED.name, DocumentIngestionStatus.PARSED.name,
    DocumentIngestionStatus.CHUNKED.name, DocumentIngestionStatus.EMBEDDED.name,
    DocumentIngestionStatus.INDEXED.name,
)
_DELETABLE_STATUSES = frozenset({MemoryVersionStatus.ACTIVE, MemoryVersionStatus.SUPERSEDED, MemoryVersionStatus.DEPRECATED})
_ALL_MEMORY_TYPE_NAMES = tuple(t.name for t in MemoryType)


class RecoverMemoryOperationsService:
    def __init__(
        self,
        document_repository: KnowledgeDocumentRepository,
        memory_repository: MemoryRepository,
        graph_node_repository: GraphNodeRepository,
        graph_edge_repository: GraphEdgeRepository,
        clock: ClockPort,
        audit_record_repository: AuditRecordRepository,
    ) -> None:
        self._document_repository = document_repository
        self._memory_repository = memory_repository
        self._graph_node_repository = graph_node_repository
        self._graph_edge_repository = graph_edge_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def scan_and_recover_ingestion(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> RecoveryScanReport:
        """10-failure-handling: "ingestion recovery：扫描 stuck document" /
        "embedding recovery：扫描 pending / failed retryable job." A document is never
        left mid-pipeline by a healthy request — IngestKnowledgeDocumentService.ingest()
        runs its whole RECEIVED→ACTIVE/FAILED sequence inside one synchronous call, and
        every intermediate `_save_transition()` durably persists each step. A row still
        sitting in a non-terminal status once it is older than the grace period
        KnowledgeDocumentRepository.find_stuck() applies can only mean the process
        crashed mid-call. There is no partial content to safely resume from (raw_content
        itself is never persisted — SPEC-MK-002's own `raw_content_ref` column stays
        unpopulated), so the only safe recovery action is the same one an in-process
        failure would have taken: mark_failed(), so a caller can see it and retry the
        whole ingest() call fresh, exactly as 10-failure-handling's own "Poison Document"
        §"可由 admin 修正 metadata 或 content 后重试" already prescribes for any FAILED
        document.
        """
        with tracer.start_as_current_span("recovery.decision"):
            now = self._clock.now()
            cutoff = now - _STUCK_GRACE_PERIOD
            stuck = self._document_repository.find_stuck(_STUCK_INGESTION_STATUSES, cutoff, batch_size)

            recovered = 0
            for document in stuck:
                failed = document.mark_failed("recovery scan: ingestion pipeline abandoned mid-transition (process crash)")
                self._document_repository.save(failed, expected_status=document.ingestion_status.name)
                recovered += 1
                self._audit_recorder.record(
                    "RECOVERY_DECISION", "recover_stuck_ingestion", "KNOWLEDGE_DOCUMENT", str(document.document_id),
                    outcome="SUCCESS", actor_id="recovery-worker",
                )

            logger.info("action=scan_and_recover_ingestion status=completed scanned=%s recovered=%s", len(stuck), recovered)
            return RecoveryScanReport(scanned=len(stuck), recovered=recovered, scanned_at=now)

    def scan_and_recover_publish_graph(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> RecoveryScanReport:
        """10-failure-handling: "graph recovery：扫描 graph extraction/upsert failed
        jobs." PublishMemoryService saves the new MemoryVersion as ACTIVE *before*
        upserting its own graph nodes/edges (07-data-model's own partial-unique-index
        ordering constraint requires the version write to land first) — a crash in that
        narrow window leaves an ACTIVE MemoryVersion with no MEMORY_VERSION graph node
        at all. Recovery re-runs the same idempotent, stable-key-deduped upsert
        PublishMemoryService._upsert_publish_graph() itself performs (a MEMORY identity
        node, a MEMORY_VERSION node, and — if this version supersedes another — the
        SUPERSEDES edge plus hiding the previous version's node), so a version that
        legitimately has no missing node is always a no-op here, never a duplicate.
        """
        with tracer.start_as_current_span("recovery.decision"):
            now = self._clock.now()
            recovered = 0
            versions = self._memory_repository.find_active_versions_by_type(_ALL_MEMORY_TYPE_NAMES, limit=batch_size)
            for version in versions:
                version_stable_key = f"memory_version:{version.memory_version_id}"
                if self._graph_node_repository.find_by_stable_key(version_stable_key, GraphNodeType.MEMORY_VERSION) is not None:
                    continue

                memory = self._memory_repository.find_memory_by_id(version.memory_id)
                if memory is None:
                    continue  # defensive only — the FK relationship makes this unreachable in practice

                memory_stable_key = f"memory:{memory.memory_id}"
                if self._graph_node_repository.find_by_stable_key(memory_stable_key, GraphNodeType.MEMORY) is None:
                    self._graph_node_repository.save(GraphNode.create(
                        GraphNodeId.new_id(), GraphNodeType.MEMORY, memory_stable_key, version.summary,
                        "INTERNAL", version.source_refs, now,
                    ))

                self._graph_node_repository.save(GraphNode.create(
                    GraphNodeId.new_id(), GraphNodeType.MEMORY_VERSION, version_stable_key, version.summary,
                    "INTERNAL", version.source_refs, now,
                ))

                if version.supersedes_version_id is not None:
                    previous_node = self._graph_node_repository.find_by_stable_key(
                        f"memory_version:{version.supersedes_version_id}", GraphNodeType.MEMORY_VERSION,
                    )
                    if previous_node is not None:
                        edge_source_hash = hashlib.sha256(f"{version_stable_key}|{previous_node.node_id}|SUPERSEDES".encode()).hexdigest()
                        current_node = self._graph_node_repository.find_by_stable_key(version_stable_key, GraphNodeType.MEMORY_VERSION)
                        if current_node is not None and self._graph_edge_repository.find_by_natural_key(
                            current_node.node_id, previous_node.node_id, GraphEdgeType.SUPERSEDES.name, edge_source_hash,
                        ) is None:
                            self._graph_edge_repository.save(GraphEdge.create(
                                GraphEdgeId.new_id(), GraphEdgeType.SUPERSEDES, current_node.node_id, previous_node.node_id,
                                1.0, version.source_refs, edge_source_hash, now,
                            ))
                        if previous_node.status is GraphNodeStatus.VISIBLE:
                            self._graph_node_repository.save(previous_node.hide())

                recovered += 1
                self._audit_recorder.record(
                    "RECOVERY_DECISION", "recover_publish_graph", "MEMORY_VERSION", str(version.memory_version_id),
                    outcome="SUCCESS", actor_id="recovery-worker",
                )

            logger.info("action=scan_and_recover_publish_graph status=completed scanned=%s recovered=%s", len(versions), recovered)
            return RecoveryScanReport(scanned=len(versions), recovered=recovered, scanned_at=now)

    def scan_and_recover_retention(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> RecoveryScanReport:
        """10-failure-handling: "retention recovery：扫描 partially applied deletion."
        ExecuteRetentionService._do_delete() deletes every deletable MemoryVersion
        *before* tombstoning the Memory's own graph node and adjacent edges — a crash
        between those two steps leaves a Memory with zero non-deleted versions but a
        still-VISIBLE MEMORY graph node. Recovery finishes the tombstone step exactly
        as _do_delete() itself would have; GraphNode.tombstone()/GraphEdge.tombstone()
        are both already unconditional (no guard against a target already TOMBSTONED),
        so re-running this against a node that was actually already fully tombstoned
        (never reachable via this scan's own VISIBLE-status filter, but defensively
        true regardless) would still be a safe no-op.
        """
        with tracer.start_as_current_span("recovery.decision"):
            now = self._clock.now()
            candidates = self._graph_node_repository.find_by_type_and_status(GraphNodeType.MEMORY, GraphNodeStatus.VISIBLE, batch_size)

            recovered = 0
            for node in candidates:
                memory_id_str = node.stable_key.removeprefix("memory:")
                try:
                    memory_id = MemoryId(uuid.UUID(memory_id_str))
                except ValueError:
                    continue  # defensive only — every MEMORY node this codebase creates uses this exact stable_key shape
                versions = self._memory_repository.find_versions(memory_id)
                if any(v.status in _DELETABLE_STATUSES for v in versions):
                    continue  # a legitimately active Memory — not a partial deletion

                for edge in self._graph_edge_repository.find_adjacent(node.node_id, limit=500):
                    self._graph_edge_repository.save(edge.tombstone())
                self._graph_node_repository.save(node.tombstone())
                recovered += 1
                self._audit_recorder.record(
                    "RECOVERY_DECISION", "recover_retention", "GRAPH_NODE", str(node.node_id),
                    outcome="SUCCESS", actor_id="recovery-worker",
                )

            logger.info("action=scan_and_recover_retention status=completed scanned=%s recovered=%s", len(candidates), recovered)
            return RecoveryScanReport(scanned=len(candidates), recovered=recovered, scanned_at=now)
