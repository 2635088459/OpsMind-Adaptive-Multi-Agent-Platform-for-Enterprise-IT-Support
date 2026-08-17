"""Mapping boundary for the admin surface — only ever builds application-layer
commands/DTOs, never memoryknowledge.domain types directly in the wire shape.
"""

from __future__ import annotations

from uuid import UUID

from memoryknowledge.application.commands import (
    ArchiveWorkingMemoryCommand,
    DeleteMemoryCommand,
    DeleteWorkingMemoryCommand,
    DeprecateMemoryCommand,
    ExpandKnowledgeGraphCommand,
    ExtractMemoryCandidateCommand,
    IngestKnowledgeDocumentCommand,
    PublishMemoryCommand,
    ReindexDocumentCommand,
    RejectMemoryCandidateCommand,
    RetryDocumentIngestionCommand,
    ValidateMemoryCandidateCommand,
)
from memoryknowledge.application.records import AuditRecordEntry
from memoryknowledge.application.views import (
    DeletionReport,
    DispatchReport,
    GraphExpansionView,
    KnowledgeDocumentView,
    MemoryCandidateView,
    MemoryVersionView,
    PoisonEventView,
    RecoveryScanReport,
)
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import (
    CorrelationId,
    GraphNodeId,
    IdempotencyKey,
    KnowledgeDocumentId,
    MemoryCandidateId,
    MemoryId,
    WorkingMemoryId,
)
from memoryknowledge.domain.values import AccessScope, SourceRef
from memoryknowledge.interfaces.admin.schemas import (
    ApproveCandidateRequest,
    ArchiveWorkingMemoryRequest,
    AuditEventResponse,
    DeleteWorkingMemoryRequest,
    DeletionReportResponse,
    DeletionRequestRequest,
    DeprecateMemoryRequest,
    DispatchReportResponse,
    ExtractCandidateRequest,
    GraphExpansionResponse,
    GraphPathResponse,
    IngestDocumentRequest,
    KnowledgeDocumentResponse,
    MemoryCandidateResponse,
    MemoryVersionResponse,
    PoisonEventListResponse,
    PoisonEventResponse,
    RecoveryScanReportResponse,
    ReindexDocumentRequest,
    RejectCandidateRequest,
    RetryDocumentRequest,
    ValidateCandidateRequest,
)


def to_ingest_command(request: IngestDocumentRequest) -> IngestKnowledgeDocumentCommand:
    return IngestKnowledgeDocumentCommand(
        source_system=request.source_system, external_id=request.external_id, title=request.title,
        document_type=request.document_type, version=request.version, raw_content=request.raw_content,
        ingested_by=request.ingested_by, acl=tuple(request.acl), classification=request.classification,
        effective_from=request.effective_from, expires_at=request.expires_at, extract_graph=request.extract_graph,
        graph_namespace=request.graph_namespace,
    )


def to_document_response(view: KnowledgeDocumentView) -> KnowledgeDocumentResponse:
    return KnowledgeDocumentResponse(
        document_id=view.document_id.value, version=view.version, ingestion_status=view.ingestion_status.name,
        title=view.title, chunk_count=view.chunk_count, created_at=view.created_at,
    )


def to_retry_command(document_id: UUID, request: RetryDocumentRequest) -> RetryDocumentIngestionCommand:
    return RetryDocumentIngestionCommand(
        document_id=KnowledgeDocumentId(document_id), raw_content=request.raw_content, retried_by=request.retried_by,
        extract_graph=request.extract_graph, graph_namespace=request.graph_namespace,
    )


def to_reindex_command(document_id: UUID, request: ReindexDocumentRequest) -> ReindexDocumentCommand:
    return ReindexDocumentCommand(
        document_id=KnowledgeDocumentId(document_id), requested_by=request.requested_by,
        extract_graph=request.extract_graph, graph_namespace=request.graph_namespace,
    )


def to_extract_command(request: ExtractCandidateRequest) -> ExtractMemoryCandidateCommand:
    return ExtractMemoryCandidateCommand(
        memory_type=MemoryType[request.memory_type], candidate_text=request.candidate_text,
        idempotency_key=IdempotencyKey(request.idempotency_key), extracted_by=request.extracted_by,
        source_refs=tuple(SourceRef(r.source_type, r.source_id, r.version, r.field_path) for r in request.source_refs),
    )


def to_validate_command(candidate_id: UUID, request: ValidateCandidateRequest) -> ValidateMemoryCandidateCommand:
    return ValidateMemoryCandidateCommand(
        candidate_id=MemoryCandidateId(candidate_id), source_refs_trusted=request.source_refs_trusted,
        confidence_score=request.confidence_score, conflict_set_id=request.conflict_set_id,
    )


def to_reject_command(candidate_id: UUID, request: RejectCandidateRequest, actor_id: str) -> RejectMemoryCandidateCommand:
    return RejectMemoryCandidateCommand(candidate_id=MemoryCandidateId(candidate_id), reason=request.reason, actor_id=actor_id)


def to_publish_command(candidate_id: UUID, request: ApproveCandidateRequest) -> PublishMemoryCommand:
    return PublishMemoryCommand(
        candidate_id=MemoryCandidateId(candidate_id), usefulness_score=request.usefulness_score,
        published_by=request.published_by, idempotency_key=IdempotencyKey(request.idempotency_key),
        content=request.content, summary=request.summary, source_trust_score=request.source_trust_score,
        memory_id=MemoryId(request.memory_id) if request.memory_id is not None else None,
        classification=request.classification,
    )


def to_candidate_response(view: MemoryCandidateView) -> MemoryCandidateResponse:
    return MemoryCandidateResponse(
        candidate_id=view.candidate_id.value, status=view.status.name, memory_type=view.memory_type,
        confidence_score=view.confidence_score, usefulness_score=view.usefulness_score, review_required=view.review_required,
        duplicate_of_memory_id=view.duplicate_of_memory_id.value if view.duplicate_of_memory_id else None,
        conflict_set_id=view.conflict_set_id, rejection_reason=view.rejection_reason, created_at=view.created_at,
    )


def to_version_response(view: MemoryVersionView) -> MemoryVersionResponse:
    return MemoryVersionResponse(
        memory_id=view.memory_id.value, memory_version_id=view.memory_version_id.value, version=view.version,
        status=view.status.name, summary=view.summary, confidence_score=view.confidence_score,
        source_trust_score=view.source_trust_score, created_at=view.created_at,
    )


def to_deprecate_command(memory_id: UUID, request: DeprecateMemoryRequest, actor_id: str) -> DeprecateMemoryCommand:
    return DeprecateMemoryCommand(memory_id=MemoryId(memory_id), actor_id=actor_id, idempotency_key=IdempotencyKey(request.idempotency_key))


def to_delete_command(request: DeletionRequestRequest, actor_id: str) -> DeleteMemoryCommand:
    return DeleteMemoryCommand(
        memory_id=MemoryId(request.memory_id), reason=request.reason, actor_id=actor_id,
        idempotency_key=IdempotencyKey(request.idempotency_key),
    )


def to_deletion_response(report: DeletionReport) -> DeletionReportResponse:
    return DeletionReportResponse(
        memory_id=report.memory_id.value, versions_deleted=report.versions_deleted,
        graph_nodes_tombstoned=report.graph_nodes_tombstoned, graph_edges_tombstoned=report.graph_edges_tombstoned,
    )


def to_expand_command(node_id: UUID, actor_id: str, max_depth: int) -> ExpandKnowledgeGraphCommand:
    return ExpandKnowledgeGraphCommand(
        seed_node_ids=(GraphNodeId(node_id),),
        access_scope=AccessScope(tenant="internal", role="admin", classification="RESTRICTED"),
        requester_type="admin", requester_id=actor_id, max_depth=max_depth, allow_deep_traversal=True,
    )


def to_expansion_response(view: GraphExpansionView) -> GraphExpansionResponse:
    return GraphExpansionResponse(
        truncated=view.truncated,
        paths=[GraphPathResponse(nodes=list(p.node_ids), edges=list(p.edge_ids), path_score=p.path_score, explanation=p.explanation) for p in view.paths],
    )


def to_dispatch_response(report: DispatchReport) -> DispatchReportResponse:
    return DispatchReportResponse(scanned=report.scanned, published=report.published, failed=report.failed, dead_lettered=report.dead_lettered)


def to_archive_working_memory_command(
    working_memory_id: UUID, request: ArchiveWorkingMemoryRequest, actor_id: str
) -> ArchiveWorkingMemoryCommand:
    return ArchiveWorkingMemoryCommand(
        working_memory_id=WorkingMemoryId(working_memory_id), expected_version=request.expected_version,
        actor_id=actor_id, correlation_id=CorrelationId(request.correlation_id),
    )


def to_delete_working_memory_command(
    working_memory_id: UUID, request: DeleteWorkingMemoryRequest, actor_id: str
) -> DeleteWorkingMemoryCommand:
    return DeleteWorkingMemoryCommand(
        working_memory_id=WorkingMemoryId(working_memory_id), expected_version=request.expected_version,
        actor_id=actor_id, correlation_id=CorrelationId(request.correlation_id),
    )


def to_audit_event_response(entry: AuditRecordEntry) -> AuditEventResponse:
    return AuditEventResponse(
        id=entry.id, audit_type=entry.audit_type, action=entry.action, resource_type=entry.resource_type,
        resource_id=entry.resource_id, ticket_id=entry.ticket_id, actor_type=entry.actor_type, actor_id=entry.actor_id,
        outcome=entry.outcome, correlation_id=entry.correlation_id, causation_id=entry.causation_id, detail=entry.detail,
        occurred_at=entry.occurred_at,
    )


def to_poison_event_response(view: PoisonEventView) -> PoisonEventResponse:
    return PoisonEventResponse(
        id=view.id, event_id=view.event_id, consumer_name=view.consumer_name, event_type=view.event_type,
        payload=view.payload, error_message=view.error_message, occurred_at=view.occurred_at,
        recorded_at=view.recorded_at, quarantined_at=view.quarantined_at,
    )


def to_poison_event_list_response(views: list[PoisonEventView]) -> PoisonEventListResponse:
    return PoisonEventListResponse(poison_events=[to_poison_event_response(view) for view in views])


def to_recovery_scan_report_response(report: RecoveryScanReport) -> RecoveryScanReportResponse:
    return RecoveryScanReportResponse(scanned=report.scanned, recovered=report.recovered, scanned_at=report.scanned_at)
