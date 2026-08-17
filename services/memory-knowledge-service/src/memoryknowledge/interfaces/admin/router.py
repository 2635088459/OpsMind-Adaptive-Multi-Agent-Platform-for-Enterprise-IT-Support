"""13-package-and-class-design §"Interfaces": internal admin controller
(05-api-contracts §"Admin API"). Depends only on ports_in Protocols. "Admin API 必须携带
actor id" — X-Actor-Id is required wherever the command itself has no other actor field.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, Header, status

from memoryknowledge.application.ports_in import (
    AuditRecordQueryPort,
    ExecuteRetentionUseCase,
    ExpandKnowledgeGraphUseCase,
    ExtractMemoryCandidateUseCase,
    IngestKnowledgeDocumentUseCase,
    OutboxDispatchPort,
    PoisonEventCommandPort,
    PoisonEventQueryPort,
    PublishMemoryUseCase,
    RecoveryPort,
    ValidateMemoryCandidateUseCase,
    WorkingMemoryLifecycleUseCase,
)
from memoryknowledge.container import (
    get_audit_record_query_port,
    get_execute_retention_port,
    get_expand_knowledge_graph_port,
    get_extract_memory_candidate_port,
    get_ingest_knowledge_document_port,
    get_outbox_dispatch_port,
    get_poison_event_command_port,
    get_poison_event_query_port,
    get_publish_memory_port,
    get_recovery_port,
    get_validate_memory_candidate_port,
    get_working_memory_lifecycle_port,
)
from memoryknowledge.interfaces.admin.mapper import (
    to_archive_working_memory_command,
    to_audit_event_response,
    to_candidate_response,
    to_delete_command,
    to_delete_working_memory_command,
    to_deletion_response,
    to_deprecate_command,
    to_dispatch_response,
    to_document_response,
    to_expand_command,
    to_expansion_response,
    to_extract_command,
    to_ingest_command,
    to_poison_event_list_response,
    to_poison_event_response,
    to_publish_command,
    to_recovery_scan_report_response,
    to_reindex_command,
    to_reject_command,
    to_retry_command,
    to_validate_command,
    to_version_response,
)
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
from memoryknowledge.interfaces.rest.mapper import to_working_memory_response
from memoryknowledge.interfaces.rest.schemas import WorkingMemoryResponse

router = APIRouter(prefix="/internal/memory/v1/admin", tags=["memory-admin"])


@router.post("/documents", status_code=status.HTTP_201_CREATED, response_model=KnowledgeDocumentResponse)
def ingest_document(request: IngestDocumentRequest, port: IngestKnowledgeDocumentUseCase = Depends(get_ingest_knowledge_document_port)) -> KnowledgeDocumentResponse:
    """05-api-contracts: `POST /internal/memory/v1/admin/documents`."""
    return to_document_response(port.ingest(to_ingest_command(request)))


@router.post("/documents/{document_id}/retry", response_model=KnowledgeDocumentResponse)
def retry_document(
    document_id: UUID, request: RetryDocumentRequest, port: IngestKnowledgeDocumentUseCase = Depends(get_ingest_knowledge_document_port),
) -> KnowledgeDocumentResponse:
    """SPEC-MK-030 05-api-contracts §"Admin API": `POST .../documents/{documentId}/retry`
    — 10-failure-handling §"Poison Document": "可由 admin 修正 metadata 或 content 后重试."
    """
    return to_document_response(port.retry(to_retry_command(document_id, request)))


@router.post("/documents/{document_id}/reindex", response_model=KnowledgeDocumentResponse)
def reindex_document(
    document_id: UUID, request: ReindexDocumentRequest, port: IngestKnowledgeDocumentUseCase = Depends(get_ingest_knowledge_document_port),
) -> KnowledgeDocumentResponse:
    """SPEC-MK-030 05-api-contracts §"Admin API": `POST .../documents/{documentId}/reindex`."""
    return to_document_response(port.reindex(to_reindex_command(document_id, request)))


@router.post("/candidates", status_code=status.HTTP_201_CREATED, response_model=MemoryCandidateResponse)
def extract_candidate(request: ExtractCandidateRequest, port: ExtractMemoryCandidateUseCase = Depends(get_extract_memory_candidate_port)) -> MemoryCandidateResponse:
    """Operational addition beyond 05-api-contracts' literal list — the pipeline entry
    point ExtractMemoryCandidateUseCase needs an HTTP path to be exercisable at all
    before phase-03 (memory-candidate-pipeline) wires event-driven extraction.
    """
    return to_candidate_response(port.extract(to_extract_command(request)))


@router.post("/candidates/{candidate_id}/validate", response_model=MemoryCandidateResponse)
def validate_candidate(
    candidate_id: UUID, request: ValidateCandidateRequest, port: ValidateMemoryCandidateUseCase = Depends(get_validate_memory_candidate_port),
) -> MemoryCandidateResponse:
    """Operational addition, mirroring extract_candidate's own reasoning — drives
    EXTRACTED -> REDACTED -> VALIDATED -> {VALIDATED | DUPLICATE | CONFLICTING}.
    """
    return to_candidate_response(port.validate(to_validate_command(candidate_id, request)))


@router.post("/candidates/{candidate_id}/reject", response_model=MemoryCandidateResponse)
def reject_candidate(
    candidate_id: UUID, request: RejectCandidateRequest, port: ValidateMemoryCandidateUseCase = Depends(get_validate_memory_candidate_port),
    actor_id: str = Header(alias="X-Actor-Id"),
) -> MemoryCandidateResponse:
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`."""
    return to_candidate_response(port.reject(to_reject_command(candidate_id, request, actor_id)))


@router.post("/candidates/{candidate_id}/approve", response_model=MemoryVersionResponse)
def approve_candidate(
    candidate_id: UUID, request: ApproveCandidateRequest, port: PublishMemoryUseCase = Depends(get_publish_memory_port),
) -> MemoryVersionResponse:
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/approve`
    ("批准候选 memory 并触发 publish")."""
    return to_version_response(port.publish(to_publish_command(candidate_id, request)))


@router.post("/memories/{memory_id}/deprecate", response_model=MemoryVersionResponse)
def deprecate_memory(
    memory_id: UUID, request: DeprecateMemoryRequest, port: ExecuteRetentionUseCase = Depends(get_execute_retention_port),
    actor_id: str = Header(alias="X-Actor-Id"),
) -> MemoryVersionResponse:
    """05-api-contracts: `POST /internal/memory/v1/admin/memories/{memoryId}/deprecate`."""
    return to_version_response(port.deprecate(to_deprecate_command(memory_id, request, actor_id)))


@router.post("/deletion-requests", response_model=DeletionReportResponse)
def create_deletion_request(
    request: DeletionRequestRequest, port: ExecuteRetentionUseCase = Depends(get_execute_retention_port),
    actor_id: str = Header(alias="X-Actor-Id"),
) -> DeletionReportResponse:
    """05-api-contracts: `POST /internal/memory/v1/admin/deletion-requests`."""
    return to_deletion_response(port.delete(to_delete_command(request, actor_id)))


@router.post("/working-memory/{working_memory_id}/archive", response_model=WorkingMemoryResponse)
def archive_working_memory(
    working_memory_id: UUID, request: ArchiveWorkingMemoryRequest, port: WorkingMemoryLifecycleUseCase = Depends(get_working_memory_lifecycle_port),
    actor_id: str = Header(alias="X-Actor-Id"),
) -> WorkingMemoryResponse:
    """SPEC-MK-006 05-api-contracts: `POST /internal/memory/v1/admin/working-memory/
    {workingMemoryId}/archive`. 03-state-machine: "ticket cycle 结束后可 ARCHIVED" —
    admin-triggered today (see WorkingMemoryLifecycleUseCase's own docstring).
    """
    return to_working_memory_response(port.archive(to_archive_working_memory_command(working_memory_id, request, actor_id)))


@router.post("/working-memory/{working_memory_id}/delete", response_model=WorkingMemoryResponse)
def delete_working_memory(
    working_memory_id: UUID, request: DeleteWorkingMemoryRequest, port: WorkingMemoryLifecycleUseCase = Depends(get_working_memory_lifecycle_port),
    actor_id: str = Header(alias="X-Actor-Id"),
) -> WorkingMemoryResponse:
    """SPEC-MK-006 03-state-machine: "deletion request 可把 body 清空并保留 tombstone."."""
    return to_working_memory_response(port.delete(to_delete_working_memory_command(working_memory_id, request, actor_id)))


@router.get("/graph/nodes/{node_id}", response_model=GraphExpansionResponse)
def get_graph_node(
    node_id: UUID, port: ExpandKnowledgeGraphUseCase = Depends(get_expand_knowledge_graph_port),
    actor_id: str = Header(alias="X-Actor-Id"), max_depth: int = 1,
) -> GraphExpansionResponse:
    """05-api-contracts: `GET /internal/memory/v1/admin/graph/nodes/{nodeId}` ("查询 graph
    node、相邻边和来源. 仅 admin/debug 使用"). Reuses ExpandKnowledgeGraphUseCase with a
    single seed rather than a dedicated read model.
    """
    return to_expansion_response(port.expand(to_expand_command(node_id, actor_id, max_depth)))


@router.post("/outbox/dispatch", response_model=DispatchReportResponse)
def dispatch_outbox(port: OutboxDispatchPort = Depends(get_outbox_dispatch_port), actor_id: str = Header(alias="X-Actor-Id")) -> DispatchReportResponse:
    """SPEC-MK-003 08-transaction-and-outbox: operational trigger for
    DispatchOutboxEventsService, mirroring agent-runtime-service's own
    `POST /admin/outbox/dispatch`.
    """
    return to_dispatch_response(port.dispatch_due_events(batch_size=50))


@router.post("/outbox/replay-dead-letter", response_model=DispatchReportResponse)
def replay_dead_letter(port: OutboxDispatchPort = Depends(get_outbox_dispatch_port), actor_id: str = Header(alias="X-Actor-Id")) -> DispatchReportResponse:
    """SPEC-MK-003 08-transaction-and-outbox §"Outbox Publisher": "replay 必须幂等" — the
    manual/ops intervention OutboxRepository.requeue()'s own docstring names.
    """
    return to_dispatch_response(port.replay_dead_letter(batch_size=50))


@router.get("/audit-events", response_model=list[AuditEventResponse])
def list_audit_events(port: AuditRecordQueryPort = Depends(get_audit_record_query_port), actor_id: str = Header(alias="X-Actor-Id")) -> list[AuditEventResponse]:
    """SPEC-MK-003 12-observability §"Audit Events" visibility surface."""
    return [to_audit_event_response(entry) for entry in port.list_audit_events(limit=100)]


@router.get("/poison-events", response_model=PoisonEventListResponse)
def list_poison_events(port: PoisonEventQueryPort = Depends(get_poison_event_query_port), actor_id: str = Header(alias="X-Actor-Id")) -> PoisonEventListResponse:
    """SPEC-MK-029 10-failure-handling §"Poison Event" step 4: "等待人工修复后 replay" —
    the visibility surface an operator uses to see what needs fixing before replaying
    it (by resending the corrected event under the same eventId to its original
    endpoint).
    """
    return to_poison_event_list_response(port.list_poison_events(limit=100))


@router.post("/poison-events/{id}/quarantine", response_model=PoisonEventResponse)
def quarantine_poison_event(
    id: UUID, port: PoisonEventCommandPort = Depends(get_poison_event_command_port), actor_id: str = Header(alias="X-Actor-Id"),
) -> PoisonEventResponse:
    """SPEC-MK-029 05-api-contracts §"Admin API": "mark poison event quarantined" —
    lets an operator flag a poison event as already triaged, distinguishing "seen"
    from "brand new" on the /poison-events visibility surface. A one-way flag, not a
    status machine — the event's only other exit is replay (resending the corrected
    event under the same eventId).
    """
    return to_poison_event_response(port.mark_quarantined(id))


@router.post("/recovery/ingestion", response_model=RecoveryScanReportResponse)
def recover_ingestion(port: RecoveryPort = Depends(get_recovery_port), actor_id: str = Header(alias="X-Actor-Id")) -> RecoveryScanReportResponse:
    """SPEC-MK-029 10-failure-handling §"Recovery Workers": "ingestion recovery" /
    "embedding recovery" — manual/ops trigger until a real periodic scheduler exists
    (mirrors OutboxDispatchPort's own "nothing schedules this periodically yet"
    precedent).
    """
    return to_recovery_scan_report_response(port.scan_and_recover_ingestion(batch_size=50))


@router.post("/recovery/publish-graph", response_model=RecoveryScanReportResponse)
def recover_publish_graph(port: RecoveryPort = Depends(get_recovery_port), actor_id: str = Header(alias="X-Actor-Id")) -> RecoveryScanReportResponse:
    """SPEC-MK-029 10-failure-handling §"Recovery Workers": "graph recovery"."""
    return to_recovery_scan_report_response(port.scan_and_recover_publish_graph(batch_size=50))


@router.post("/recovery/retention", response_model=RecoveryScanReportResponse)
def recover_retention(port: RecoveryPort = Depends(get_recovery_port), actor_id: str = Header(alias="X-Actor-Id")) -> RecoveryScanReportResponse:
    """SPEC-MK-029 10-failure-handling §"Recovery Workers": "retention recovery"."""
    return to_recovery_scan_report_response(port.scan_and_recover_retention(batch_size=50))
