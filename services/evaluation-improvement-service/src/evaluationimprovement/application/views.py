"""Read-model DTOs returned by application.ports_in use cases — never a domain
aggregate reference itself, mirroring memory-knowledge-service's own application.views
convention (13-package-and-class-design §"Application Layer": `views.py`).
"""

from __future__ import annotations

from dataclasses import dataclass
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
    RiskLevel,
    RunStatus,
    ScoreFailureCode,
)
from evaluationimprovement.domain.ids import CandidateId, DatasetId, ReportId, RunId, ScoreId, TestCaseId


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
    score_id: ScoreId
    run_id: RunId
    test_case_id: TestCaseId
    dimension: EvaluationDimension
    score: float
    passed: bool
    grader_type: GraderType
    grader_version: str
    failure_code: ScoreFailureCode | None


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
class ImprovementCandidateView:
    candidate_id: CandidateId
    candidate_type: CandidateType
    source_run_id: RunId
    target_component: str
    risk_level: RiskLevel
    status: CandidateStatus
    created_by: str
    approved_by: str | None
    approval_request_id: str | None
    canary_status: CanaryStatus | None
    promoted_version: str | None
    created_at: datetime
    updated_at: datetime


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
class AuditRecordView:
    action: str
    resource_type: str
    resource_id: str
    actor: str
    outcome: str
    occurred_at: datetime
    detail: dict[str, Any]
