"""13-package-and-class-design §"Interfaces": internal admin controller
(05-api-contracts §"Admin API"). Depends only on ports_in Protocols. "Admin API 必须携带
actor id" — X-Actor-Id is required wherever the command itself has no other actor field.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, Header, status

from memoryknowledge.application.ports_in import (
    ExecuteRetentionUseCase,
    ExpandKnowledgeGraphUseCase,
    ExtractMemoryCandidateUseCase,
    IngestKnowledgeDocumentUseCase,
    OutboxDispatchPort,
    PublishMemoryUseCase,
    ValidateMemoryCandidateUseCase,
)
from memoryknowledge.container import (
    get_execute_retention_port,
    get_expand_knowledge_graph_port,
    get_extract_memory_candidate_port,
    get_ingest_knowledge_document_port,
    get_outbox_dispatch_port,
    get_publish_memory_port,
    get_validate_memory_candidate_port,
)
from memoryknowledge.interfaces.admin.mapper import (
    to_candidate_response,
    to_delete_command,
    to_deletion_response,
    to_deprecate_command,
    to_dispatch_response,
    to_document_response,
    to_expand_command,
    to_expansion_response,
    to_extract_command,
    to_ingest_command,
    to_publish_command,
    to_reject_command,
    to_validate_command,
    to_version_response,
)
from memoryknowledge.interfaces.admin.schemas import (
    ApproveCandidateRequest,
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
    RejectCandidateRequest,
    ValidateCandidateRequest,
)

router = APIRouter(prefix="/internal/memory/v1/admin", tags=["memory-admin"])


@router.post("/documents", status_code=status.HTTP_201_CREATED, response_model=KnowledgeDocumentResponse)
def ingest_document(request: IngestDocumentRequest, port: IngestKnowledgeDocumentUseCase = Depends(get_ingest_knowledge_document_port)) -> KnowledgeDocumentResponse:
    """05-api-contracts: `POST /internal/memory/v1/admin/documents`."""
    return to_document_response(port.ingest(to_ingest_command(request)))


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
) -> MemoryCandidateResponse:
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`."""
    return to_candidate_response(port.reject(to_reject_command(candidate_id, request)))


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
    """08-transaction-and-outbox (deferred detail to SPEC-MK-003): operational trigger
    for DispatchOutboxEventsService, mirroring agent-runtime-service's own
    `POST /admin/outbox/dispatch`.
    """
    return to_dispatch_response(port.dispatch_due_events(batch_size=50))
