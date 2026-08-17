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
    KnowledgeDocumentId,
    MemoryCandidateId,
    MemoryId,
    TicketCycleId,
    TicketId,
    WorkflowInstanceId,
    WorkingMemoryId,
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
    Memory — 01-domain-model: "同一个 scope 只能有一个 active WorkingMemory". SPEC-MK-005
    api-contract §"通用约束": "Internal API 必须携带 correlation id."
    """

    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    workflow_instance_id: WorkflowInstanceId
    expected_version: int
    updated_by: str
    correlation_id: CorrelationId
    add_facts: tuple[str, ...] = ()
    add_hypotheses: tuple[str, ...] = ()
    reject_hypotheses: tuple[RejectHypothesisInput, ...] = ()
    complete_tasks: tuple[str, ...] = ()
    add_pending_tasks: tuple[str, ...] = ()
    add_tool_evidence_refs: tuple[ToolEvidenceRefInput, ...] = ()
    add_approval_decision_refs: tuple[str, ...] = ()
    context_summary: str | None = None


@dataclass(frozen=True, slots=True)
class QueryWorkingMemoryCommand:
    """SPEC-MK-006 05-api-contracts: `GET /internal/memory/v1/working-memory/{workingMemoryId}`."""

    working_memory_id: WorkingMemoryId
    correlation_id: CorrelationId


@dataclass(frozen=True, slots=True)
class ArchiveWorkingMemoryCommand:
    """SPEC-MK-006 03-state-machine §"Working Memory 状态": "ticket cycle 结束后可
    ARCHIVED." 09-concurrency-and-idempotency's own idempotency-key table entry for
    Working Memory is `workingMemoryId + expectedVersion` — reused here, not a
    separate IdempotencyKey, since this is the same version-protected aggregate
    UpdateWorkingMemoryCommand already uses that mechanism for.
    """

    working_memory_id: WorkingMemoryId
    expected_version: int
    actor_id: str
    correlation_id: CorrelationId


@dataclass(frozen=True, slots=True)
class DeleteWorkingMemoryCommand:
    """SPEC-MK-006 03-state-machine: "deletion request 可把 body 清空并保留 tombstone."."""

    working_memory_id: WorkingMemoryId
    expected_version: int
    actor_id: str
    correlation_id: CorrelationId


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
    """05-api-contracts: `POST /internal/memory/v1/admin/documents`. `classification`
    defaults to "INTERNAL" (07-data-model `memory.knowledge_documents` §"classification
    text not null") — SPEC-MK-025's own job is making this caller-settable.
    """

    source_system: str
    external_id: str
    title: str
    document_type: str
    version: int
    raw_content: str
    ingested_by: str
    acl: tuple[str, ...] = ()
    classification: str = "INTERNAL"
    effective_from: datetime | None = None
    expires_at: datetime | None = None
    extract_graph: bool = False
    graph_namespace: str | None = None


@dataclass(frozen=True, slots=True)
class RetryDocumentIngestionCommand:
    """SPEC-MK-030 05-api-contracts §"Admin API": `POST .../documents/{documentId}/retry`
    — 10-failure-handling §"Poison Document": "可由 admin 修正 metadata 或 content 后重试."
    raw_content is always required — see KnowledgeDocument.retry()'s own docstring for
    why nothing from the original failed attempt is stored to replay.
    """

    document_id: KnowledgeDocumentId
    raw_content: str
    retried_by: str
    extract_graph: bool = False
    graph_namespace: str | None = None


@dataclass(frozen=True, slots=True)
class ReindexDocumentCommand:
    """SPEC-MK-030 05-api-contracts §"Admin API": `POST .../documents/{documentId}/reindex`
    — re-runs graph extraction/upsert against an already-ACTIVE document's existing,
    immutable chunk content (recovering from an entity-extractor bug/rule-set
    upgrade). Never touches chunks or embeddings — see
    IngestKnowledgeDocumentService.reindex()'s own docstring for why re-embedding in
    place would violate this codebase's own chunk/embedding immutability contracts.
    """

    document_id: KnowledgeDocumentId
    requested_by: str
    extract_graph: bool = False
    graph_namespace: str | None = None


@dataclass(frozen=True, slots=True)
class ExtractMemoryCandidateCommand:
    """13-package-and-class-design: ExtractMemoryCandidateUseCase. Source-agnostic by
    design — SPEC-MK-010's own ConsumeTicketMemorySourceEventService is one caller
    (event-driven extraction from ticket.resolved.v1/ticket.closed.v1), a direct
    admin/evaluation caller is another; workflow.completed.v1-driven extraction
    remains a later phase's own consumer to add.
    """

    memory_type: MemoryType
    source_refs: tuple[SourceRef, ...]
    candidate_text: str
    idempotency_key: IdempotencyKey
    extracted_by: str


@dataclass(frozen=True, slots=True)
class ConsumeTicketResolvedCommand:
    """SPEC-MK-010 06-event-contracts: consumed `ticket.resolved.v1`
    (02-ticket-workflow PUB-012, Consumers: "Memory、Evaluation、Notification"). Field
    names mirror that service's own real published payload
    (TicketResolvedEventMapper: supportQueueId/assigneeId/resolutionCycleId/
    previousStatus/newStatus/resolutionCode/resolutionSummary/resolvedBy/resolvedAt/
    autoCloseDueAt), not 04-memory-knowledge's own 06-event-contracts illustrative
    sketch (which names resolutionSummary/verificationStatus fields that don't exist
    on the real event 02 actually publishes) — "02 remains system of record" (this
    spec's own domain-rule) means 02's real schema wins. resolutionCycleId is the same
    "distinguish cycles across reopen" concept 04's own aggregates call
    ticketCycleId — confirmed by agent-runtime-service's own SPEC-ARO-023 event schema
    mapping the identical field the same way.
    """

    event_id: str
    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    resolution_code: str
    resolution_summary: str
    resolved_by: str
    resolved_at: datetime
    correlation_id: CorrelationId


@dataclass(frozen=True, slots=True)
class ConsumeTicketClosedCommand:
    """SPEC-MK-010 06-event-contracts: consumed `ticket.closed.v1` (02-ticket-workflow
    PUB-013, Consumers: "Memory、Evaluation、Analytics")."""

    event_id: str
    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    close_reason_code: str
    close_reason: str
    closed_by: str
    closed_at: datetime
    correlation_id: CorrelationId


@dataclass(frozen=True, slots=True)
class ConsumeWorkflowCompletedCommand:
    """SPEC-MK-022 06-event-contracts: consumed `workflow.completed.v1`
    (03-agent-runtime-orchestration's own real published payload —
    CompleteWorkflowService._to_payload: workflowInstanceId/fromState/toState/
    workflowVersion/occurredAt; envelope carries ticketId, no ticketCycleId — that
    service's own OutboxRecord never carries one). 04-memory-knowledge's own
    06-event-contracts sketch ("获取 automation trace、task summaries、tool evidence
    refs") describes richer content than the real event actually carries today; "02
    remains system of record" applies symmetrically here to 03 — this command only
    has the fields the real event does.
    """

    event_id: str
    workflow_instance_id: WorkflowInstanceId
    ticket_id: TicketId
    from_state: str | None
    to_state: str
    workflow_version: int
    occurred_at: datetime
    correlation_id: CorrelationId


@dataclass(frozen=True, slots=True)
class ConsumeWorkflowFailedCommand:
    """SPEC-MK-022 06-event-contracts: consumed `workflow.failed.v1`
    (FailWorkflowService's own real payload: adds failureReason)."""

    event_id: str
    workflow_instance_id: WorkflowInstanceId
    ticket_id: TicketId
    from_state: str | None
    to_state: str
    workflow_version: int
    failure_reason: str
    occurred_at: datetime
    correlation_id: CorrelationId


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
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`.
    05-api-contracts §"API 原则": "Admin API 必须写 audit" — actor_id is the X-Actor-Id
    header, required the same way DeprecateMemoryCommand/DeleteMemoryCommand carry one.
    """

    candidate_id: MemoryCandidateId
    reason: str
    actor_id: str


@dataclass(frozen=True, slots=True)
class PublishMemoryCommand:
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/approve`
    ("批准候选 memory 并触发 publish") — 03-state-machine: "APPROVED -> PUBLISHED 必须在同一
    事务中创建 MemoryVersion 和 outbox event", so approve and publish are one command here,
    not two round trips. `memory_id` is None to create a brand new Memory identity
    (the original default behavior); set it to publish this candidate as the next
    version of an *existing* Memory instead — UC-05 step 1 "创建 Memory 或定位 existing
    Memory" — which supersedes that Memory's current active version
    (08-transaction-and-outbox §"Publish Memory Transaction" steps 3-4). No LLD
    section describes an automatic algorithm for choosing which existing Memory a
    candidate should supersede, so this stays a caller-supplied decision (an admin
    reviewing the candidate), not something this command infers on its own.
    `classification` defaults to "INTERNAL" (07-data-model `memory.memories`
    §"classification text not null") — SPEC-MK-025's own job is making this a real,
    caller-settable value instead of always the DB column's own default; no LLD
    section names an automatic classification-inference algorithm, so this too
    stays caller-supplied.
    """

    candidate_id: MemoryCandidateId
    usefulness_score: float
    published_by: str
    idempotency_key: IdempotencyKey
    content: str
    summary: str
    source_trust_score: float
    memory_id: MemoryId | None = None
    classification: str = "INTERNAL"


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
