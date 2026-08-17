"""Read DTOs returned by application services to memoryknowledge.interfaces — mirrors
agent-runtime-service's own application.views. Kept separate from domain aggregates so
interfaces mapping doesn't need to know which domain module a given field originally
lived in; each view builds itself from the domain object it wraps.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from memoryknowledge.domain.enums import DocumentIngestionStatus, MemoryCandidateStatus, MemoryVersionStatus, WorkingMemoryStatus
from memoryknowledge.domain.ids import KnowledgeDocumentId, MemoryCandidateId, MemoryId, MemoryVersionId, RetrievalId, WorkingMemoryId
from memoryknowledge.domain.knowledge_document import KnowledgeDocument
from memoryknowledge.domain.memory import MemoryVersion
from memoryknowledge.domain.memory_candidate import MemoryCandidate
from memoryknowledge.domain.values import GraphPath, RetrievalResultItem
from memoryknowledge.domain.working_memory import RejectedHypothesis, ToolEvidenceRef, WorkingMemory


@dataclass(frozen=True, slots=True)
class WorkingMemoryView:
    """SPEC-MK-004 01-domain-model §"WorkingMemory": every field the aggregate carries
    is projected here — rejected_hypotheses/tool_evidence_refs/approval_decision_refs
    were missing from this view since SPEC-MK-001 (present on the domain object,
    unreachable through the read side), a real projection-completeness gap this spec
    closes.
    """

    working_memory_id: WorkingMemoryId
    version: int
    status: WorkingMemoryStatus
    facts: tuple[str, ...]
    hypotheses: tuple[str, ...]
    rejected_hypotheses: tuple[RejectedHypothesis, ...]
    completed_tasks: tuple[str, ...]
    pending_tasks: tuple[str, ...]
    tool_evidence_refs: tuple[ToolEvidenceRef, ...]
    approval_decision_refs: tuple[str, ...]
    context_summary: str
    updated_at: datetime

    @staticmethod
    def from_domain(working_memory: WorkingMemory) -> "WorkingMemoryView":
        return WorkingMemoryView(
            working_memory_id=working_memory.working_memory_id, version=working_memory.version,
            status=working_memory.status, facts=working_memory.facts, hypotheses=working_memory.hypotheses,
            rejected_hypotheses=working_memory.rejected_hypotheses,
            completed_tasks=working_memory.completed_tasks, pending_tasks=working_memory.pending_tasks,
            tool_evidence_refs=working_memory.tool_evidence_refs, approval_decision_refs=working_memory.approval_decision_refs,
            context_summary=working_memory.context_summary, updated_at=working_memory.updated_at,
        )


@dataclass(frozen=True, slots=True)
class MemoryCandidateView:
    candidate_id: MemoryCandidateId
    status: MemoryCandidateStatus
    memory_type: str
    confidence_score: float | None
    usefulness_score: float | None
    review_required: bool
    duplicate_of_memory_id: MemoryId | None
    conflict_set_id: str | None
    rejection_reason: str | None
    created_at: datetime

    @staticmethod
    def from_domain(candidate: MemoryCandidate) -> "MemoryCandidateView":
        return MemoryCandidateView(
            candidate_id=candidate.candidate_id, status=candidate.status, memory_type=candidate.memory_type.name,
            confidence_score=candidate.confidence_score, usefulness_score=candidate.usefulness_score,
            review_required=candidate.review_required, duplicate_of_memory_id=candidate.duplicate_of_memory_id,
            conflict_set_id=candidate.conflict_set_id, rejection_reason=candidate.rejection_reason,
            created_at=candidate.created_at,
        )


@dataclass(frozen=True, slots=True)
class MemoryVersionView:
    memory_id: MemoryId
    memory_version_id: MemoryVersionId
    version: int
    status: MemoryVersionStatus
    summary: str
    confidence_score: float
    source_trust_score: float
    created_at: datetime

    @staticmethod
    def from_domain(memory_version: MemoryVersion) -> "MemoryVersionView":
        return MemoryVersionView(
            memory_id=memory_version.memory_id, memory_version_id=memory_version.memory_version_id,
            version=memory_version.version, status=memory_version.status, summary=memory_version.summary,
            confidence_score=memory_version.confidence_score, source_trust_score=memory_version.source_trust_score,
            created_at=memory_version.created_at,
        )


@dataclass(frozen=True, slots=True)
class KnowledgeDocumentView:
    document_id: KnowledgeDocumentId
    version: int
    ingestion_status: DocumentIngestionStatus
    title: str
    chunk_count: int
    created_at: datetime

    @staticmethod
    def from_domain(document: KnowledgeDocument, chunk_count: int) -> "KnowledgeDocumentView":
        return KnowledgeDocumentView(
            document_id=document.document_id, version=document.version, ingestion_status=document.ingestion_status,
            title=document.title, chunk_count=chunk_count, created_at=document.created_at,
        )


@dataclass(frozen=True, slots=True)
class SearchResultView:
    """05-api-contracts §"Runtime API" `POST /internal/memory/v1/search` response shape.
    10-failure-handling §"Retrieval Degraded": `degraded_reason` accompanies a fully
    degraded search (e.g. "REPOSITORY_UNAVAILABLE"); §"Graph Failure": `graph_degraded`
    is independent — the base vector/keyword results can still be non-degraded while
    just the graph-expansion sub-step failed.
    """

    retrieval_id: RetrievalId
    degraded: bool
    results: tuple[RetrievalResultItem, ...]
    degraded_reason: str | None = None
    graph_degraded: bool = False


@dataclass(frozen=True, slots=True)
class GraphExpansionView:
    """05-api-contracts: graph expansion result — bounded paths from the requested seeds,
    already ACL/classification-filtered.
    """

    paths: tuple[GraphPath, ...]
    truncated: bool
    """True when max_nodes was reached before traversal naturally exhausted —
    02-business-invariants: "Graph expansion 不能绕过 ACL / classification filter" callers
    must not read a truncated result as "no further relationships exist".
    """


@dataclass(frozen=True, slots=True)
class DispatchReport:
    """08-transaction-and-outbox (deferred detail to SPEC-MK-003): outcome of one
    OutboxDispatchPort.dispatch_due_events() batch call.
    """

    scanned: int
    published: int
    failed: int
    dead_lettered: int


@dataclass(frozen=True, slots=True)
class DeletionReport:
    """05-api-contracts: `POST /internal/memory/v1/admin/deletion-requests` result —
    03-state-machine §"Deletion 状态机": "删除必须覆盖: memory content、memory versions、
    embeddings、document chunks、retrieval visibility 和 cache."
    """

    memory_id: MemoryId
    versions_deleted: int
    graph_nodes_tombstoned: int
    graph_edges_tombstoned: int
