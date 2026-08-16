"""Application-layer commands — the input shape for every memoryknowledge.application.
ports_in Protocol method. SPEC-MK-001 domain-rules: "需要写状态的命令必须具备幂等或版本
保护" — each write command below carries either an expected_version (WorkingMemory), a
natural uniqueness key the repository enforces (IngestKnowledgeDocumentCommand's
source_system+external_id+version), or an idempotency_key (everything else that writes).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType, MemoryType
from memoryknowledge.domain.ids import (
    CorrelationId,
    GraphNodeId,
    IdempotencyKey,
    MemoryCandidateId,
    MemoryId,
    TicketCycleId,
    TicketId,
    WorkflowInstanceId,
)
from memoryknowledge.domain.values import AccessScope, SourceRef


@dataclass(frozen=True, slots=True)
class RejectHypothesisInput:
    hypothesis: str
    reason: str


@dataclass(frozen=True, slots=True)
class ToolEvidenceRefInput:
    tool_request_id: str
    summary: str
    status: str
    evidence_hash: str


@dataclass(frozen=True, slots=True)
class UpdateWorkingMemoryCommand:
    """05-api-contracts: `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`
    "必须传 expectedVersion". expected_version == 0 with no existing row for the
    (ticket_id, ticket_cycle_id, workflow_instance_id) scope creates a new Working
    Memory — 01-domain-model: "同一个 scope 只能有一个 active WorkingMemory".
    """

    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    workflow_instance_id: WorkflowInstanceId
    expected_version: int
    updated_by: str
    add_facts: tuple[str, ...] = ()
    add_hypotheses: tuple[str, ...] = ()
    reject_hypotheses: tuple[RejectHypothesisInput, ...] = ()
    complete_tasks: tuple[str, ...] = ()
    add_pending_tasks: tuple[str, ...] = ()
    add_tool_evidence_refs: tuple[ToolEvidenceRefInput, ...] = ()
    add_approval_decision_refs: tuple[str, ...] = ()
    context_summary: str | None = None


@dataclass(frozen=True, slots=True)
class SearchMemoryCommand:
    """05-api-contracts: `POST /internal/memory/v1/search`."""

    query: str
    requester_type: str
    requester_id: str
    access_scope: AccessScope
    correlation_id: CorrelationId
    ticket_id: TicketId | None = None
    ticket_cycle_id: TicketCycleId | None = None
    workflow_instance_id: WorkflowInstanceId | None = None
    memory_types: tuple[MemoryType, ...] = ()
    max_results: int = 8
    include_graph_paths: bool = True
    max_graph_depth: int = 2


@dataclass(frozen=True, slots=True)
class ExpandKnowledgeGraphCommand:
    """01-domain-model / 02-business-invariants: "graph traversal depth MVP 默认不超过 2,
    除非 admin/research API 明确提升" — allow_deep_traversal is that explicit admin/
    research escape hatch.
    """

    seed_node_ids: tuple[GraphNodeId, ...]
    access_scope: AccessScope
    requester_type: str
    requester_id: str
    max_depth: int = 2
    allow_deep_traversal: bool = False
    max_nodes: int = 50


@dataclass(frozen=True, slots=True)
class IngestKnowledgeDocumentCommand:
    """05-api-contracts: `POST /internal/memory/v1/admin/documents`."""

    source_system: str
    external_id: str
    title: str
    document_type: str
    version: int
    raw_content: str
    ingested_by: str
    acl: tuple[str, ...] = ()
    effective_from: datetime | None = None
    expires_at: datetime | None = None
    extract_graph: bool = False
    graph_namespace: str | None = None


@dataclass(frozen=True, slots=True)
class ExtractMemoryCandidateCommand:
    """13-package-and-class-design: ExtractMemoryCandidateUseCase. Source-agnostic by
    design at this spec's scope — event-driven extraction from ticket.resolved.v1 /
    workflow.completed.v1 (06-event-contracts) is phase-03 (memory-candidate-pipeline);
    this command is the pipeline entry point any future event consumer, or a direct
    admin/evaluation caller, feeds evidence into today.
    """

    memory_type: MemoryType
    source_refs: tuple[SourceRef, ...]
    candidate_text: str
    idempotency_key: IdempotencyKey
    extracted_by: str


@dataclass(frozen=True, slots=True)
class ValidateMemoryCandidateCommand:
    """13-package-and-class-design: ValidateMemoryCandidateUseCase. Drives
    EXTRACTED -> REDACTED -> VALIDATED -> {VALIDATED | DUPLICATE | CONFLICTING} as one
    orchestrated step (03-state-machine).
    """

    candidate_id: MemoryCandidateId
    source_refs_trusted: bool
    confidence_score: float
    conflict_set_id: str | None = None
    """Set by the caller (a human reviewer or a future policy port — 02-business-
    invariants: "CONFLICTING candidate 必须人工或 policy 处理") when this candidate is
    known to conflict with an existing active Memory. Automatic conflict *detection*
    (semantic comparison across active memories) is deferred; this spec only guarantees
    that once flagged, the CONFLICTING path cannot auto-publish.
    """


@dataclass(frozen=True, slots=True)
class RejectMemoryCandidateCommand:
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`."""

    candidate_id: MemoryCandidateId
    reason: str


@dataclass(frozen=True, slots=True)
class PublishMemoryCommand:
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/approve`
    ("批准候选 memory 并触发 publish") — 03-state-machine: "APPROVED -> PUBLISHED 必须在同一
    事务中创建 MemoryVersion 和 outbox event", so approve and publish are one command here,
    not two round trips.
    """

    candidate_id: MemoryCandidateId
    usefulness_score: float
    published_by: str
    idempotency_key: IdempotencyKey
    content: str
    summary: str
    source_trust_score: float


@dataclass(frozen=True, slots=True)
class DeprecateMemoryCommand:
    """05-api-contracts: `POST /internal/memory/v1/admin/memories/{memoryId}/deprecate`."""

    memory_id: MemoryId
    actor_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class DeleteMemoryCommand:
    """05-api-contracts: `POST /internal/memory/v1/admin/deletion-requests`. "执行前必须
    通过 policy / authorization" — AuthorizationPort is consulted before this applies.
    Deliberately synchronous (REQUESTED -> APPLIED in one call): the fuller
    REQUESTED/AUTHORIZED/APPLIED/VERIFIED audit trail 03-state-machine §"Deletion 状态机"
    describes is phase-07 (security-and-governance) scope, not this boundaries spec.
    """

    memory_id: MemoryId
    reason: str
    actor_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class GraphEntityInput:
    """Application-layer shape for a candidate graph entity an EntityExtractorPort
    proposes from redacted content — kept separate from domain.knowledge_graph.GraphNode
    since it has no node_id yet (the repository assigns one only after the stableKey
    uniqueness check resolves whether this is a new node or an existing one).
    """

    node_type: GraphNodeType
    normalized_name: str
    display_name: str
    source_refs: tuple[SourceRef, ...] = field(default_factory=tuple)


@dataclass(frozen=True, slots=True)
class GraphRelationInput:
    edge_type: GraphEdgeType
    from_entity: GraphEntityInput
    to_entity: GraphEntityInput
    confidence: float
    evidence_refs: tuple[SourceRef, ...]
