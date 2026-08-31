"""13-package-and-class-design §"Interfaces": "Controllers do not contain business
rules. They only perform request validation, auth, and DTO mapping." pydantic
request/response models for 05-api-contracts's Dataset/Run/Report/Candidate API.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


class CreateDatasetRequest(BaseModel):
    name: str = Field(min_length=1)
    version: str = Field(min_length=1)
    domain: str = Field(min_length=1)
    scenario_tags: list[str] = Field(default_factory=list)
    created_by: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)
    lineage_parent_id: UUID | None = None


class DatasetResponse(BaseModel):
    dataset_id: UUID
    name: str
    version: str
    domain: str
    status: str
    case_count: int
    created_by: str
    published_by: str | None
    created_at: datetime
    published_at: datetime | None
    content_hash: str | None = None
    tenant_id: str = "default"


class TestCaseInputRequest(BaseModel):
    case_key: str = Field(min_length=1)
    scenario: str = Field(min_length=1)
    user_request_redacted: str = ""
    mock_system_state: dict[str, Any] = Field(default_factory=dict)
    ground_truth: dict[str, Any]
    allowed_tools: list[str] = Field(default_factory=list)
    forbidden_tools: list[str] = Field(default_factory=list)
    required_approval: bool = False
    verification_condition: dict[str, Any] = Field(default_factory=dict)
    criticality: str = "STANDARD"


class AddTestCasesRequest(BaseModel):
    cases: list[TestCaseInputRequest] = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class TestCaseResponse(BaseModel):
    test_case_id: UUID
    dataset_id: UUID
    case_key: str
    scenario: str
    user_request_redacted: str
    mock_system_state: dict[str, Any]
    ground_truth: dict[str, Any]
    allowed_tools: list[str]
    forbidden_tools: list[str]
    required_approval: bool
    verification_condition: dict[str, Any]
    criticality: str
    input_hash: str


class PublishDatasetRequest(BaseModel):
    published_by: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class SubmitDatasetForReviewRequest(BaseModel):
    correlation_id: str = Field(min_length=1)


class RejectDatasetReviewRequest(BaseModel):
    reason: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class DeprecateDatasetRequest(BaseModel):
    correlation_id: str = Field(min_length=1)


class ArchiveDatasetRequest(BaseModel):
    correlation_id: str = Field(min_length=1)


class CreateDatasetVersionRequest(BaseModel):
    new_version: str = Field(min_length=1)
    created_by: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class CreateRunRequest(BaseModel):
    run_key: str = Field(min_length=1)
    dataset_id: UUID
    dataset_version: str = ""  # not required in the request per 05-api-contracts sample; dataset's own PUBLISHED version is authoritative
    target_version: str = Field(min_length=1)
    baseline_version: str | None = None
    grader_bundle_version: str = "grader-bundle-v1"
    policy_version: str = "policy-v1"
    gate_policy: str = Field(min_length=1)
    triggered_by: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class RunResponse(BaseModel):
    run_id: UUID
    run_key: str
    dataset_id: UUID
    dataset_version: str
    target_version: str
    baseline_version: str | None
    status: str
    triggered_by: str
    started_at: datetime
    completed_at: datetime | None


class CancelRunRequest(BaseModel):
    reason: str = ""
    correlation_id: str = Field(min_length=1)


class SkipCaseRequest(BaseModel):
    reason: str = ""
    correlation_id: str = Field(min_length=1)


class EvidenceRefResponse(BaseModel):
    artifact_provider: str
    artifact_uri: str
    artifact_hash: str
    retention_until: str | None


class ScoreResponse(BaseModel):
    score_id: UUID
    run_id: UUID
    test_case_id: UUID
    dimension: str
    score: float
    passed: bool
    grader_type: str
    grader_version: str
    failure_code: str | None
    # SPEC-EI-034 (evaluation-security-redaction-observability): None/{} for any
    # viewer without can_view_sensitive_evidence() — see
    # CreateRunService.find_scores()'s own docstring.
    evidence: EvidenceRefResponse | None = None
    details: dict[str, Any] = {}


class FailureClusterResponse(BaseModel):
    """SPEC-EI-023 (failure-clustering-root-cause-taxonomy)."""

    cluster_id: str
    run_id: UUID
    dimension: str
    failure_code: str
    case_count: int
    test_case_ids: list[UUID]


class CollectOnlineSampleRequest(BaseModel):
    """SPEC-EI-028 (online-sample-evaluation) — see
    application.commands.CollectOnlineSampleCommand's own docstring for what "already
    selected/already redacted" means here.
    """

    candidate_id: UUID | None = None
    target_version: str = Field(min_length=1)
    source_event_type: str = Field(min_length=1)
    source_trace_ref: str = Field(min_length=1)
    redacted_context: dict[str, Any] = {}
    correlation_id: str = Field(min_length=1)


class OnlineEvaluationSampleResponse(BaseModel):
    sample_id: UUID
    candidate_id: UUID | None
    target_version: str
    source_event_type: str
    source_trace_ref: str
    status: str
    collected_at: datetime
    scored_at: datetime | None
    composite_score: float | None
    failure_code: str | None


class CanaryPromotionDecisionResponse(BaseModel):
    """SPEC-EI-029 (promotion-criteria-rollback-request): a recommendation only —
    see application.views.CanaryPromotionDecisionView's own docstring.
    """

    candidate_id: UUID
    eligible_to_advance: bool
    recommend_rollback: bool
    sample_count: int
    required_sample_size: int
    error_rate: float | None
    reason: str


class RegressionReportResponse(BaseModel):
    report_id: UUID
    run_id: UUID
    baseline_run_id: UUID | None
    overall_decision: str
    critical_failures: list[str]
    recommendation: str
    created_at: datetime


class CreateImprovementCandidateRequest(BaseModel):
    candidate_type: str = Field(min_length=1)
    source_run_id: UUID
    source_failure_cluster_id: str | None = None
    target_component: str = Field(min_length=1)
    proposed_change: dict[str, Any]
    risk_level: str = "LOW"
    created_by: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)


class ImprovementCandidateResponse(BaseModel):
    candidate_id: UUID
    candidate_type: str
    source_run_id: UUID
    target_component: str
    risk_level: str
    status: str
    created_by: str
    benchmark_run_id: UUID | None
    benchmark_passed: bool
    approved_by: str | None
    approval_request_id: str | None
    canary_status: str | None
    promoted_version: str | None
    created_at: datetime
    updated_at: datetime


class RecordCandidateBenchmarkRequest(BaseModel):
    benchmark_run_id: UUID
    correlation_id: str = Field(min_length=1)


class RequestCandidateApprovalRequest(BaseModel):
    correlation_id: str = Field(min_length=1)


class ApproveCandidateRequest(BaseModel):
    approved_by: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class RejectCandidateRequest(BaseModel):
    reason: str = ""
    correlation_id: str = Field(min_length=1)


class CanaryStageRequest(BaseModel):
    traffic_percent: float = Field(gt=0, le=100)
    min_duration_minutes: int = Field(gt=0)
    rollback_error_rate_threshold: float = Field(ge=0, le=1)
    sample_size: int = Field(default=1, gt=0)


class StartCanaryRequest(BaseModel):
    plan_version: str = Field(min_length=1)
    stages: list[CanaryStageRequest] = Field(min_length=1)
    correlation_id: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)


class RollbackCandidateRequest(BaseModel):
    reason: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)


class AdvanceCanaryRequest(BaseModel):
    """SPEC-EI-036 (evaluation-contract-e2e-harness-final-release): closes a real gap
    the phase's own final coverage audit found — advance()/promote() were real
    ManageCanaryUseCase/CreateImprovementCandidateUseCase methods with no REST
    endpoint at all, meaning a candidate approved and canary-started over HTTP could
    never actually reach PROMOTED through the API.
    """

    correlation_id: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)


class PauseCanaryRequest(BaseModel):
    reason: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)


class CompleteCanaryRollbackRequest(BaseModel):
    correlation_id: str = Field(min_length=1)


class PromoteCandidateRequest(BaseModel):
    promoted_version: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class RollbackPromotedCandidateRequest(BaseModel):
    reason: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)
