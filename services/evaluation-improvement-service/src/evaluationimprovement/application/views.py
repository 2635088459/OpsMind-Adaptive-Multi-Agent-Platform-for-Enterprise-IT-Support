"""Read-model DTOs returned by application.ports_in use cases — never a domain
aggregate reference itself, mirroring memory-knowledge-service's own application.views
convention (13-package-and-class-design §"Application Layer": `views.py`).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from evaluationimprovement.domain.enums import (
    CandidateStatus,
    CandidateType,
    CanaryStatus,
    DatasetStatus,
    EvaluationDimension,
    GateDecision,
    GraderType,
    OnlineSampleStatus,
    RiskLevel,
    RunStatus,
    ScoreFailureCode,
)
from evaluationimprovement.domain.ids import CandidateId, DatasetId, ReportId, RunId, ScoreId, TestCaseId
from evaluationimprovement.domain.values import EvidenceRef


@dataclass(frozen=True, slots=True)
class DatasetView:
    dataset_id: DatasetId
    name: str
    version: str
    domain: str
    status: DatasetStatus
    case_count: int
    created_by: str
    published_by: str | None
    created_at: datetime
    published_at: datetime | None
    # SPEC-EI-007: frozen at publish() time, None before then — see
    # domain.dataset.EvaluationDataset's own docstring.
    content_hash: str | None = None
    # SPEC-EI-008: see domain.dataset.EvaluationDataset's own docstring.
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class TestCaseView:
    """SPEC-EI-005: the full test case schema — 01-domain-model already defined every
    one of these fields and 07-data-model already persists them (SPEC-EI-002), but no
    read model exposed more than test_case_id/case_key/criticality/input_hash until
    this spec.
    """

    test_case_id: TestCaseId
    dataset_id: DatasetId
    case_key: str
    scenario: str
    user_request_redacted: str
    mock_system_state: dict[str, Any]
    ground_truth: dict[str, Any]
    allowed_tools: tuple[str, ...]
    forbidden_tools: tuple[str, ...]
    required_approval: bool
    verification_condition: dict[str, Any]
    criticality: str
    input_hash: str


@dataclass(frozen=True, slots=True)
class RunView:
    run_id: RunId
    run_key: str
    dataset_id: DatasetId
    dataset_version: str
    target_version: str
    baseline_version: str | None
    status: RunStatus
    triggered_by: str
    started_at: datetime
    completed_at: datetime | None


@dataclass(frozen=True, slots=True)
class ScoreView:
    """SPEC-EI-034 (evaluation-security-redaction-observability) / 11-security
    §"数据保护": "Report 默认展示聚合分数；case-level evidence 需要更高权限." `evidence_ref`/
    `details` are the sensitive case-level evidence that rule gates — always populated
    here (the view itself is the full truth), but
    RunQueryUseCase.find_scores()'s own caller-role check strips both to None/{}
    before this ever reaches a viewer without `can_view_sensitive_evidence()`.
    """

    score_id: ScoreId
    run_id: RunId
    test_case_id: TestCaseId
    dimension: EvaluationDimension
    score: float
    passed: bool
    grader_type: GraderType
    grader_version: str
    failure_code: ScoreFailureCode | None
    evidence_ref: EvidenceRef | None = None
    details: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class RegressionReportView:
    report_id: ReportId
    run_id: RunId
    baseline_run_id: RunId | None
    overall_decision: GateDecision
    critical_failures: tuple[str, ...]
    recommendation: str
    created_at: datetime


@dataclass(frozen=True, slots=True)
class CiGateOutcome:
    """SPEC-EI-022 (ci-evaluation-gate-harness): what a CI job actually needs back —
    `passed` is the one field an exit-code decision reads; `run_status`/`reason` are
    for a human/log to see why a run that never reached a release-gate decision
    (still QUEUED, or PARTIAL) still counts as not passed.
    """

    run_id: RunId
    run_status: str
    gate_decision: str | None
    critical_failures: tuple[str, ...]
    reason: str
    passed: bool


@dataclass(frozen=True, slots=True)
class ImprovementCandidateView:
    candidate_id: CandidateId
    candidate_type: CandidateType
    source_run_id: RunId
    target_component: str
    risk_level: RiskLevel
    status: CandidateStatus
    created_by: str
    benchmark_run_id: RunId | None
    benchmark_passed: bool
    approved_by: str | None
    approval_request_id: str | None
    canary_status: CanaryStatus | None
    promoted_version: str | None
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class FailureClusterView:
    """SPEC-EI-023 (failure-clustering-root-cause-taxonomy): a `(dimension,
    failure_code)` root-cause taxonomy category, derived at query time from a run's
    own failed EvaluationScore rows — see
    application.services.cluster_run_failures.ClusterRunFailuresService's own
    docstring. `cluster_id` is a stable string (`"{dimension}:{failure_code}"}`), not
    a random UUID, so it can be reused directly as
    CreateImprovementCandidateCommand.source_failure_cluster_id.
    """

    cluster_id: str
    run_id: RunId
    dimension: str
    failure_code: ScoreFailureCode
    case_count: int
    test_case_ids: tuple[TestCaseId, ...]


@dataclass(frozen=True, slots=True)
class GatePolicyView:
    gate_policy: str
    dimension_thresholds: dict[str, float]
    critical_case_required: bool
    max_policy_violations: int
    max_forbidden_tool_calls: int
    max_unauthorized_memory_access: int


@dataclass(frozen=True, slots=True)
class GraderDescriptor:
    """05-api-contracts §"管理 API": `GET /evaluation/graders`."""

    name: str
    grader_type: GraderType
    dimension: EvaluationDimension
    version: str


@dataclass(frozen=True, slots=True)
class DispatchReport:
    dispatched: int
    failed: int
    dead_lettered: int


@dataclass(frozen=True, slots=True)
class CaseRunnerReport:
    """SPEC-EI-011: CaseRunnerService.run_once()'s own summary — mirrors
    DispatchReport's shape/purpose exactly, for the case-execution work queue instead
    of the outbox.
    """

    claimed: int
    completed: int
    retried: int
    exhausted: int


@dataclass(frozen=True, slots=True)
class OnlineEvaluationSampleView:
    """SPEC-EI-028 (online-sample-evaluation)."""

    sample_id: uuid.UUID
    candidate_id: CandidateId | None
    target_version: str
    source_event_type: str
    source_trace_ref: str
    status: OnlineSampleStatus
    collected_at: datetime
    scored_at: datetime | None
    composite_score: float | None
    failure_code: ScoreFailureCode | None


@dataclass(frozen=True, slots=True)
class CanaryPromotionDecisionView:
    """SPEC-EI-029 (promotion-criteria-rollback-request): a pure recommendation, never
    an executed action — 07 only requests rollback / recommends advance, the caller
    (an admin, or a future automated trigger) still drives
    ManageCanaryUseCase.advance()/request_rollback() itself. Mirrors
    CiGateOutcome's own "what a caller needs back" shape.
    """

    candidate_id: CandidateId
    eligible_to_advance: bool
    recommend_rollback: bool
    sample_count: int
    required_sample_size: int
    error_rate: float | None
    reason: str


@dataclass(frozen=True, slots=True)
class OnlineSampleScoringReport:
    """SPEC-EI-028: CollectOnlineSampleService.score_pending()'s own summary — mirrors
    CaseRunnerReport's shape/purpose exactly, for the online-sample queue instead of
    the case-execution queue.
    """

    scored: int
    mean_composite_score: float | None


@dataclass(frozen=True, slots=True)
class AuditRecordView:
    action: str
    resource_type: str
    resource_id: str
    actor: str
    outcome: str
    occurred_at: datetime
    detail: dict[str, Any]
