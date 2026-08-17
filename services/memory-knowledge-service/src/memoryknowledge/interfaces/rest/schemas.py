"""13-package-and-class-design §"Interfaces": "Controllers do not contain business
rules. They only perform request validation, auth, and DTO mapping." pydantic
request/response models for the Runtime-facing REST surface (05-api-contracts
§"Runtime API").
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class AccessScopeRequest(BaseModel):
    tenant: str = Field(min_length=1)
    role: str = Field(min_length=1)
    classification: str = Field(min_length=1)
    application: str | None = None
    queue: str | None = None


class SearchFiltersRequest(BaseModel):
    memory_types: list[str] = Field(default_factory=list)
    max_results: int = Field(default=8, ge=1, le=50)
    include_graph_paths: bool = True
    max_graph_depth: int = Field(default=2, ge=1)


class SearchRequest(BaseModel):
    query: str = Field(min_length=1)
    requester_type: str = Field(min_length=1)
    requester_id: str = Field(min_length=1)
    access_scope: AccessScopeRequest
    correlation_id: UUID
    ticket_id: UUID | None = None
    ticket_cycle_id: UUID | None = None
    workflow_instance_id: UUID | None = None
    filters: SearchFiltersRequest = Field(default_factory=SearchFiltersRequest)


class ProvenanceResponse(BaseModel):
    source_type: str
    source_ref: str
    redacted: bool


class GraphPathResponse(BaseModel):
    nodes: list[str]
    edges: list[str]
    path_score: float
    explanation: str


class SearchResultItemResponse(BaseModel):
    result_type: str
    source_id: str
    source_version: int
    snippet: str
    score: float
    provenance: ProvenanceResponse
    graph_paths: list[GraphPathResponse] = Field(default_factory=list)


class SearchResponse(BaseModel):
    retrieval_id: UUID
    degraded: bool
    results: list[SearchResultItemResponse]
    degraded_reason: str | None = None
    graph_degraded: bool = False


class RejectHypothesisRequest(BaseModel):
    hypothesis: str = Field(min_length=1)
    reason: str = Field(min_length=1)


class ToolEvidenceRefRequest(BaseModel):
    tool_request_id: str = Field(min_length=1)
    summary: str = Field(min_length=1)
    status: str = Field(min_length=1)
    evidence_hash: str = Field(min_length=1)


class UpdateWorkingMemoryRequest(BaseModel):
    """05-api-contracts: `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`
    "必须传 expectedVersion". ticket/cycle/workflow together are this Working Memory's
    scope-derived identity — see domain.working_memory.derive_working_memory_id.
    SPEC-MK-005 api-contract §"通用约束": "Internal API 必须携带 correlation id."
    """

    ticket_id: UUID
    ticket_cycle_id: UUID
    workflow_instance_id: UUID
    expected_version: int = Field(ge=0)
    updated_by: str = Field(min_length=1)
    correlation_id: UUID
    add_facts: list[str] = Field(default_factory=list)
    add_hypotheses: list[str] = Field(default_factory=list)
    reject_hypotheses: list[RejectHypothesisRequest] = Field(default_factory=list)
    complete_tasks: list[str] = Field(default_factory=list)
    add_pending_tasks: list[str] = Field(default_factory=list)
    add_tool_evidence_refs: list[ToolEvidenceRefRequest] = Field(default_factory=list)
    add_approval_decision_refs: list[str] = Field(default_factory=list)
    context_summary: str | None = None


class RejectedHypothesisResponse(BaseModel):
    hypothesis: str
    reason: str
    rejected_at: datetime


class ToolEvidenceRefResponse(BaseModel):
    tool_request_id: str
    summary: str
    status: str
    evidence_hash: str


class WorkingMemoryResponse(BaseModel):
    working_memory_id: UUID
    version: int
    status: str
    facts: list[str]
    hypotheses: list[str]
    rejected_hypotheses: list[RejectedHypothesisResponse]
    completed_tasks: list[str]
    pending_tasks: list[str]
    tool_evidence_refs: list[ToolEvidenceRefResponse]
    approval_decision_refs: list[str]
    context_summary: str
    updated_at: datetime
