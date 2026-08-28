"""Command DTOs for every application.ports_in use case. 05-api-contracts §"API 原则":
"写 API 必须要求 authenticated actor/service identity、idempotency key 和 correlation id" —
every state-changing command below carries `actor` and `correlation_id`; commands
without a natural uniqueness key (dataset publish, candidate create, canary
operations) also carry `idempotency_key` (09-concurrency-and-idempotency §"幂等键").
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from evaluationimprovement.domain.enums import CandidateType, Criticality, RiskLevel
from evaluationimprovement.domain.ids import CandidateId, DatasetId, IdempotencyKey, RunId, TestCaseId


@dataclass(frozen=True, slots=True)
class TestCaseInput:
    case_key: str
    scenario: str
    user_request_redacted: str
    mock_system_state: dict[str, Any]
    ground_truth: dict[str, Any]
    allowed_tools: tuple[str, ...]
    forbidden_tools: tuple[str, ...]
    required_approval: bool
    verification_condition: dict[str, Any]
    criticality: Criticality


@dataclass(frozen=True, slots=True)
class CreateDatasetCommand:
    name: str
    version: str
    domain: str
    scenario_tags: tuple[str, ...]
    created_by: str
    actor: str
    correlation_id: str
    lineage_parent_id: DatasetId | None = None
    # SPEC-EI-008 / 11-security: caller-asserted tenant scope — see
    # domain.dataset.EvaluationDataset's own docstring.
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class AddTestCasesCommand:
    dataset_id: DatasetId
    cases: tuple[TestCaseInput, ...]
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class PublishDatasetCommand:
    dataset_id: DatasetId
    published_by: str
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class SubmitDatasetForReviewCommand:
    """SPEC-EI-006: DRAFT -> REVIEWING, a distinct auditable step from publish() —
    04-use-cases UC-EI-001 step 3: "Reviewer 检查 case 是否覆盖 ..." happens while the
    dataset sits here, before anyone calls publish().
    """

    dataset_id: DatasetId
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class RejectDatasetReviewCommand:
    dataset_id: DatasetId
    reason: str
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class DeprecateDatasetCommand:
    dataset_id: DatasetId
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class ArchiveDatasetCommand:
    dataset_id: DatasetId
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class CreateDatasetVersionCommand:
    """02-business-invariants INV-EI-005: "Dataset 发布后不可变；变更必须创建新 version，并
    保留 lineage." Seeds a brand new DRAFT dataset from an already-PUBLISHED parent —
    same name/domain, a caller-supplied new version, `lineage_parent_id` bound to the
    parent, and the parent's own test cases copied forward as the new version's
    starting point (never shared rows across dataset_id, per "版本化测试资产所有权").
    """

    parent_dataset_id: DatasetId
    new_version: str
    created_by: str
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class CreateRunCommand:
    run_key: str
    dataset_id: DatasetId
    target_version: str
    baseline_version: str | None
    grader_bundle_version: str
    policy_version: str
    gate_policy: str
    triggered_by: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class CancelRunCommand:
    run_id: RunId
    reason: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ExecuteCaseCommand:
    run_id: RunId
    test_case_id: TestCaseId
    attempt: int
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class SkipCaseCommand:
    """SPEC-EI-009 / 10-failure-handling §"Partial Run": explicitly marks a case as
    never-to-be-executed for this run (the "未执行 case" a Partial report must list),
    rather than leaving it permanently unaccounted-for.
    """

    run_id: RunId
    test_case_id: TestCaseId
    reason: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ScoreCaseCommand:
    """ScoreRunService grades every dimension a case's own graders cover for one
    already-executed case; `run_generation` guards against a stale runner reply
    (09-concurrency-and-idempotency §"Stale 结果").
    """

    run_id: RunId
    test_case_id: TestCaseId
    run_generation: int
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class FinalizeRunScoringCommand:
    """Marks a run's scoring phase complete once every expected case has a score or is
    explicitly skipped/failed (08-transaction-and-outbox §"Run 完成事务").
    """

    run_id: RunId
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class CompareRegressionCommand:
    run_id: RunId
    baseline_run_id: RunId | None
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class EvaluateReleaseGateCommand:
    run_id: RunId
    gate_policy: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class CreateImprovementCandidateCommand:
    candidate_type: CandidateType
    source_run_id: RunId
    source_failure_cluster_id: str | None
    target_component: str
    proposed_change: dict[str, Any]
    risk_level: RiskLevel
    created_by: str
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class RecordCandidateBenchmarkCommand:
    candidate_id: CandidateId
    passed: bool
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class RequestCandidateApprovalCommand:
    candidate_id: CandidateId
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ApproveCandidateCommand:
    candidate_id: CandidateId
    approved_by: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class RejectCandidateCommand:
    candidate_id: CandidateId
    reason: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class CanaryStageInput:
    traffic_percent: float
    min_duration_minutes: int
    rollback_error_rate_threshold: float


@dataclass(frozen=True, slots=True)
class StartCanaryCommand:
    candidate_id: CandidateId
    plan_version: str
    stages: tuple[CanaryStageInput, ...]
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class AdvanceCanaryCommand:
    candidate_id: CandidateId
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class PauseCanaryCommand:
    candidate_id: CandidateId
    reason: str
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class RequestCanaryRollbackCommand:
    candidate_id: CandidateId
    reason: str
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class CompleteCanaryRollbackCommand:
    candidate_id: CandidateId
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class PromoteCandidateCommand:
    candidate_id: CandidateId
    promoted_version: str
    actor: str
    correlation_id: str
