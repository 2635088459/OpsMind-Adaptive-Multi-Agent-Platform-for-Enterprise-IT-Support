"""pydantic request/response models for the admin surface (05-api-contracts
§"Admin API"). 05-api-contracts §"API 原则": "Admin API 必须写 audit" — audit persistence
itself is SPEC-MK-003 (outbox/idempotency/audit baseline) scope; every admin write
below still requires an actor identity (X-Actor-Id header or a body actor field) so that
spec has something to record against.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class SourceRefRequest(BaseModel):
    source_type: str = Field(min_length=1)
    source_id: str = Field(min_length=1)
    version: int | None = None
    field_path: str | None = None


class IngestDocumentRequest(BaseModel):
    """05-api-contracts: `POST /internal/memory/v1/admin/documents`."""

    source_system: str = Field(min_length=1)
    external_id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    document_type: str = Field(min_length=1)
    raw_content: str = Field(min_length=1)
    ingested_by: str = Field(min_length=1)
    version: int = Field(default=1, ge=1)
    acl: list[str] = Field(default_factory=list)
    effective_from: datetime | None = None
    expires_at: datetime | None = None
    extract_graph: bool = False
    graph_namespace: str | None = None


class KnowledgeDocumentResponse(BaseModel):
    document_id: UUID
    version: int
    ingestion_status: str
    title: str
    chunk_count: int
    created_at: datetime


class ExtractCandidateRequest(BaseModel):
    memory_type: str = Field(min_length=1)
    source_refs: list[SourceRefRequest] = Field(min_length=1)
    candidate_text: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)
    extracted_by: str = Field(min_length=1)


class ValidateCandidateRequest(BaseModel):
    source_refs_trusted: bool
    confidence_score: float = Field(ge=0, le=1)
    conflict_set_id: str | None = None


class RejectCandidateRequest(BaseModel):
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/reject`."""

    reason: str = Field(min_length=1)


class ApproveCandidateRequest(BaseModel):
    """05-api-contracts: `POST /internal/memory/v1/admin/candidates/{candidateId}/approve`
    ("批准候选 memory 并触发 publish")."""

    usefulness_score: float = Field(ge=0, le=1)
    published_by: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)
    content: str = Field(min_length=1)
    summary: str = Field(min_length=1)
    source_trust_score: float = Field(ge=0, le=1)


class MemoryCandidateResponse(BaseModel):
    candidate_id: UUID
    status: str
    memory_type: str
    confidence_score: float | None
    usefulness_score: float | None
    review_required: bool
    duplicate_of_memory_id: UUID | None
    conflict_set_id: str | None
    rejection_reason: str | None
    created_at: datetime


class MemoryVersionResponse(BaseModel):
    memory_id: UUID
    memory_version_id: UUID
    version: int
    status: str
    summary: str
    confidence_score: float
    source_trust_score: float
    created_at: datetime


class DeprecateMemoryRequest(BaseModel):
    """05-api-contracts: `POST /internal/memory/v1/admin/memories/{memoryId}/deprecate`."""

    idempotency_key: str = Field(min_length=1)


class DeletionRequestRequest(BaseModel):
    """05-api-contracts: `POST /internal/memory/v1/admin/deletion-requests`."""

    memory_id: UUID
    reason: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)


class DeletionReportResponse(BaseModel):
    memory_id: UUID
    versions_deleted: int
    graph_nodes_tombstoned: int
    graph_edges_tombstoned: int


class GraphPathResponse(BaseModel):
    nodes: list[str]
    edges: list[str]
    path_score: float
    explanation: str


class GraphExpansionResponse(BaseModel):
    """05-api-contracts: `GET /internal/memory/v1/admin/graph/nodes/{nodeId}`."""

    paths: list[GraphPathResponse]
    truncated: bool


class DispatchReportResponse(BaseModel):
    scanned: int
    published: int
    failed: int
    dead_lettered: int
