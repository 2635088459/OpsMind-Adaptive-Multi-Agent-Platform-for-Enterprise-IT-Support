"""13-package-and-class-design §"Application Layer": PublishMemoryService, the sole
implementation of PublishMemoryUseCase. 03-state-machine: "APPROVED -> PUBLISHED 必须在
同一事务中创建 MemoryVersion 和 outbox event" — approve() and publish() both happen here,
in one command, alongside the Memory/MemoryVersion creation and outbox append (the
"transaction" is this spec's in-memory adapters; SPEC-MK-002 gives it a real Postgres
transaction boundary).
"""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime

from memoryknowledge.application.commands import PublishMemoryCommand
from memoryknowledge.application.exceptions import (
    MemoryCandidateConflictingException,
    MemoryCandidateNotFoundException,
    MemoryNotFoundException,
)
from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.ports_out import (
    AuditRecordRepository,
    AuthorizationPort,
    ClockPort,
    CommandIdempotencyRepository,
    EmbeddingProvider,
    EmbeddingRepository,
    GraphEdgeRepository,
    GraphNodeRepository,
    MemoryCandidateRepository,
    MemoryRepository,
    OutboxRepository,
    RedactionPolicyPort,
)
from memoryknowledge.application.services.audit import AuditRecorder
from memoryknowledge.application.services.idempotency import CommandIdempotencyGuard
from memoryknowledge.application.views import MemoryVersionView
from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType, MemoryVersionStatus
from memoryknowledge.domain.events import KnowledgeGraphUpdated, MemoryPublished, MemorySuperseded
from memoryknowledge.domain.ids import GraphEdgeId, GraphNodeId, MemoryId, MemoryVersionId
from memoryknowledge.domain.knowledge_graph import GraphEdge, GraphNode
from memoryknowledge.domain.memory import Memory, MemoryVersion
from memoryknowledge.domain.values import RedactionReport

_COMMAND_TYPE = "publish_memory"


class PublishMemoryService:
    def __init__(
        self, memory_candidate_repository: MemoryCandidateRepository, memory_repository: MemoryRepository,
        graph_node_repository: GraphNodeRepository, graph_edge_repository: GraphEdgeRepository,
        embedding_provider: EmbeddingProvider, embedding_repository: EmbeddingRepository,
        redaction_policy_port: RedactionPolicyPort, command_idempotency_repository: CommandIdempotencyRepository,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository,
        authorization_port: AuthorizationPort, clock: ClockPort,
    ) -> None:
        self._memory_candidate_repository = memory_candidate_repository
        self._memory_repository = memory_repository
        self._graph_node_repository = graph_node_repository
        self._graph_edge_repository = graph_edge_repository
        self._embedding_provider = embedding_provider
        self._embedding_repository = embedding_repository
        self._redaction_policy_port = redaction_policy_port
        self._outbox_repository = outbox_repository
        self._authorization_port = authorization_port
        self._clock = clock
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def publish(self, command: PublishMemoryCommand) -> MemoryVersionView:
        """SPEC-MK-003 09-concurrency-and-idempotency: "Memory publish" 幂等键是
        `candidateId` conceptually, but the actual guard key is command.idempotency_key
        (a caller-supplied value scoped to this one publish attempt) — a retried
        delivery under the same key with the same content/summary/usefulness_score
        replays the prior MemoryVersion; a different payload under the same key raises
        IdempotencyKeyReusedException.
        """
        return self._idempotency_guard.run(
            command_type=_COMMAND_TYPE, target_id=str(command.candidate_id), idempotency_key=command.idempotency_key,
            request_payload=_request_payload(command), execute=lambda: self._do_publish(command),
            to_dict=_view_to_dict, from_dict=_view_from_dict,
        )

    def _do_publish(self, command: PublishMemoryCommand) -> MemoryVersionView:
        candidate = self._memory_candidate_repository.find_by_id(command.candidate_id)
        if candidate is None:
            raise MemoryCandidateNotFoundException(command.candidate_id)

        # 02-business-invariants §"记忆写入不变量": "CONFLICTING candidate 必须人工或
        # policy 处理，不能自动覆盖 active memory"; 11-security §"Redaction Pipeline" step
        # 4 "Human review for high-risk candidate" — MemoryCandidate.redact() now also
        # sets review_required=True whenever the candidate's own raw evidence actually
        # contained secret-shaped text (see its own docstring), not only for
        # CONFLICTING. 03-state-machine still allows CONFLICTING -> APPROVED (a
        # human/policy caller legitimately can resolve one), so this does not block
        # the transition outright for either reason; it requires the same
        # AuthorizationPort gate ExecuteRetentionService already consults for its own
        # sensitive override (deletion). Reuses MEMORY_CANDIDATE_CONFLICTING
        # (05-api-contracts already names it) rather than inventing a new wire error
        # code for the broader condition — the root meaning ("this candidate needs a
        # real actor to look at it before it becomes active memory") is the same.
        if candidate.review_required:
            if not self._authorization_port.is_conflict_resolution_authorized(command.published_by, candidate.candidate_id):
                raise MemoryCandidateConflictingException(candidate.candidate_id, candidate.conflict_set_id)

        previous_status = candidate.status
        candidate = candidate.approve(command.usefulness_score)
        candidate = candidate.publish()
        candidate = self._memory_candidate_repository.save(candidate, previous_status)

        now = self._clock.now()

        # UC-05 step 1 "创建 Memory 或定位 existing Memory" — command.memory_id is None
        # for the original "always a new Memory" behavior; a caller-supplied
        # memory_id targets an existing one instead, which this publish then
        # supersedes (08-transaction-and-outbox §"Publish Memory Transaction" steps
        # 3-4: "将旧 active version 标记为 SUPERSEDED", "将新 version 标记为 ACTIVE").
        previous_active: MemoryVersion | None = None
        if command.memory_id is not None:
            memory = self._memory_repository.find_memory_by_id(command.memory_id)
            if memory is None:
                raise MemoryNotFoundException(command.memory_id)
            previous_active = self._memory_repository.find_active_version(memory.memory_id)
            existing_versions = self._memory_repository.find_versions(memory.memory_id)
            next_version = max((v.version for v in existing_versions), default=0) + 1
        else:
            memory = Memory.create(MemoryId.new_id(), candidate.memory_type, now, classification=command.classification)
            memory = self._memory_repository.save_memory(memory)
            next_version = 1

        if previous_active is not None:
            # 09-concurrency-and-idempotency §"Publish 并发": "同一 Memory 只能有一个
            # active version" — 07-data-model backs this with a real partial unique
            # index (one ACTIVE MemoryVersion per memory_id), so the old active
            # version must be superseded *before* the new one is inserted as ACTIVE,
            # never after: inserting the new ACTIVE row first would violate that
            # index while both rows are briefly ACTIVE. The CAS below is this
            # codebase's own row-lock equivalent (mirrors every other aggregate's
            # optimistic-version save).
            superseded = previous_active.supersede()
            self._memory_repository.save_version(superseded, expected_status=MemoryVersionStatus.ACTIVE)

        # 02-business-invariants: "Agent 看到的是 redacted content，不是 raw source";
        # 05-api-contracts §"API 原则": "对 Runtime 返回 redacted snippet，不返回 raw
        # document." command.content/command.summary are fresh text the approving
        # caller supplies on this exact call — distinct from candidate.redacted_text,
        # which only ever covered the *original* extracted candidate_text — so they
        # must be redacted here too, the same way UpdateWorkingMemoryService/
        # IngestKnowledgeDocumentService already redact every other piece of content
        # immediately before it becomes persisted, searchable Memory content.
        redacted_content, content_report = self._redaction_policy_port.redact(command.content)
        redacted_summary, summary_report = self._redaction_policy_port.redact(command.summary)
        redaction_report = RedactionReport(
            redacted_fields=tuple(sorted(set(content_report.redacted_fields) | set(summary_report.redacted_fields))),
            secret_patterns_matched=tuple(sorted(set(content_report.secret_patterns_matched) | set(summary_report.secret_patterns_matched))),
            policy_rule_ids=tuple(sorted(set(content_report.policy_rule_ids) | set(summary_report.policy_rule_ids))),
        )

        # 01-domain-model §"MemoryVersion" lists `embeddingRef` as a field; UC-02's own
        # hybrid retrieval (SPEC-MK-017) needs a real vector to search against, the
        # same way DocumentChunk already gets one during ingestion — publish is the
        # one place a MemoryVersion's final, redacted content is available to embed.
        embedding_ref, vector = self._embedding_provider.embed(redacted_content)
        self._embedding_repository.save(embedding_ref, vector)

        # 07-data-model / 09-concurrency-and-idempotency: source_hash is computed from
        # the content that is actually stored and searchable (the redacted form), not
        # the raw input — DUPLICATE detection (MemoryRepository.find_by_source_hash())
        # must compare against what a future search would actually return.
        source_hash = hashlib.sha256(redacted_content.encode()).hexdigest()
        version = MemoryVersion.create_active(
            memory_version_id=MemoryVersionId.new_id(), memory_id=memory.memory_id, version=next_version, content=redacted_content,
            summary=redacted_summary, source_refs=candidate.source_refs, redaction_report=redaction_report,
            confidence_score=candidate.confidence_score or 0.0, source_trust_score=command.source_trust_score,
            source_hash=source_hash, created_by=command.published_by, created_at=now, embedding_ref=embedding_ref,
            supersedes_version_id=previous_active.memory_version_id if previous_active is not None else None,
        )
        version = self._memory_repository.save_version(version, expected_status=None)

        # UC-05 step 6 "upsert graph nodes / edges，并为新版本建立 ... 关系";
        # 08-transaction-and-outbox §"Publish Memory Transaction" steps 7-8: "upsert
        # graph nodes / edges", "对 superseded version 写 SUPERSEDES edge，并隐藏旧
        # version 的默认检索 visibility." No LLD section specifies an entity/relation
        # extraction algorithm for Memory content the way documents have one
        # (EntityExtractorPort) — this stays a structural MEMORY/MEMORY_VERSION node
        # upsert plus a SUPERSEDES edge on supersession, not a fabricated extraction.
        node_count, edge_count = self._upsert_publish_graph(memory, version, previous_active, now)
        if node_count or edge_count:
            # 06-event-contracts `knowledge.graph.updated.v1`: "graph nodes / edges 被
            # ingestion 或 memory publish 更新后发布" — explicitly names memory publish
            # as a second publisher of this event, alongside
            # IngestKnowledgeDocumentService's own.
            self._outbox_repository.append(build_outbox_record(
                KnowledgeGraphUpdated(
                    graph_update_id=str(memory.memory_id), source_type="memory", source_id=str(memory.memory_id),
                    node_count=node_count, edge_count=edge_count, index_version=version.version, occurred_at=now,
                ),
                "knowledge.graph.updated.v1", aggregate_id=str(memory.memory_id), occurred_at=now,
            ))

        if previous_active is not None:
            self._outbox_repository.append(build_outbox_record(
                MemorySuperseded(
                    memory_id=memory.memory_id, superseded_version_id=previous_active.memory_version_id,
                    superseding_version_id=version.memory_version_id, occurred_at=now,
                ),
                "memory.superseded.v1", aggregate_id=str(memory.memory_id), occurred_at=now,
            ))

        self._outbox_repository.append(build_outbox_record(
            MemoryPublished(memory_id=memory.memory_id, memory_version_id=version.memory_version_id, version=version.version, occurred_at=now),
            "memory.published.v1", aggregate_id=str(memory.memory_id), occurred_at=now,
        ))
        # 12-observability §"审计动作" names approve_candidate and publish_memory as
        # separate actions; PublishMemoryCommand's own docstring explains why this
        # spec's admin API combines them into one call ("批准候选 memory 并触发 publish") —
        # the audit trail still records both distinct facts.
        self._audit_recorder.record(
            audit_type="MEMORY", action="approve_candidate", resource_type="MEMORY_CANDIDATE", resource_id=str(command.candidate_id),
            outcome="SUCCESS", actor_id=command.published_by,
        )
        self._audit_recorder.record(
            audit_type="MEMORY", action="publish_memory", resource_type="MEMORY", resource_id=str(memory.memory_id),
            outcome="SUCCESS", actor_id=command.published_by,
            detail=f'{{"candidate_id": "{command.candidate_id}", "memory_version_id": "{version.memory_version_id}"}}',
        )
        return MemoryVersionView.from_domain(version)

    def _upsert_publish_graph(
        self, memory: Memory, version: MemoryVersion, previous_active: MemoryVersion | None, now: datetime,
    ) -> tuple[int, int]:
        node_count = 0
        memory_stable_key = f"memory:{memory.memory_id}"
        memory_node = self._graph_node_repository.find_by_stable_key(memory_stable_key, GraphNodeType.MEMORY)
        if memory_node is None:
            memory_node = GraphNode.create(
                GraphNodeId.new_id(), GraphNodeType.MEMORY, memory_stable_key, version.summary,
                "INTERNAL", version.source_refs, now,
            )
            self._graph_node_repository.save(memory_node)
            node_count += 1

        version_node = GraphNode.create(
            GraphNodeId.new_id(), GraphNodeType.MEMORY_VERSION, f"memory_version:{version.memory_version_id}",
            version.summary, "INTERNAL", version.source_refs, now,
        )
        self._graph_node_repository.save(version_node)
        node_count += 1

        if previous_active is None:
            return node_count, 0
        previous_node = self._graph_node_repository.find_by_stable_key(
            f"memory_version:{previous_active.memory_version_id}", GraphNodeType.MEMORY_VERSION,
        )
        if previous_node is None:
            return node_count, 0

        # 03-state-machine §"Graph Index 状态": "MemoryVersion superseded 时，相关
        # SUPERSEDES 边新增，旧 version 节点默认 HIDDEN."
        edge_count = 0
        edge_source_hash = hashlib.sha256(f"{version_node.node_id}|{previous_node.node_id}|SUPERSEDES".encode()).hexdigest()
        if self._graph_edge_repository.find_by_natural_key(
            version_node.node_id, previous_node.node_id, GraphEdgeType.SUPERSEDES.name, edge_source_hash,
        ) is None:
            edge = GraphEdge.create(
                GraphEdgeId.new_id(), GraphEdgeType.SUPERSEDES, version_node.node_id, previous_node.node_id,
                1.0, version.source_refs, edge_source_hash, now,
            )
            self._graph_edge_repository.save(edge)
            edge_count += 1
        self._graph_node_repository.save(previous_node.hide())
        return node_count, edge_count


def _request_payload(command: PublishMemoryCommand) -> dict:
    return {
        "candidate_id": str(command.candidate_id), "usefulness_score": command.usefulness_score,
        "published_by": command.published_by, "content": command.content, "summary": command.summary,
        "source_trust_score": command.source_trust_score,
        "memory_id": str(command.memory_id) if command.memory_id is not None else None,
    }


def _view_to_dict(view: MemoryVersionView) -> dict:
    return {
        "memory_id": str(view.memory_id.value), "memory_version_id": str(view.memory_version_id.value), "version": view.version,
        "status": view.status.name, "summary": view.summary, "confidence_score": view.confidence_score,
        "source_trust_score": view.source_trust_score, "created_at": view.created_at.isoformat(),
    }


def _view_from_dict(data: dict) -> MemoryVersionView:
    return MemoryVersionView(
        memory_id=MemoryId(uuid.UUID(data["memory_id"])), memory_version_id=MemoryVersionId(uuid.UUID(data["memory_version_id"])),
        version=data["version"], status=MemoryVersionStatus[data["status"]], summary=data["summary"],
        confidence_score=data["confidence_score"], source_trust_score=data["source_trust_score"],
        created_at=datetime.fromisoformat(data["created_at"]),
    )
