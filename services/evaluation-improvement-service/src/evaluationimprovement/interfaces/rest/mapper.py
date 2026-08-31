"""13-package-and-class-design §"Interfaces": DTO mapping only, no business rules."""

from __future__ import annotations

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    AdvanceCanaryCommand,
    ApproveCandidateCommand,
    ArchiveDatasetCommand,
    CancelRunCommand,
    CanaryStageInput,
    CollectOnlineSampleCommand,
    CompleteCanaryRollbackCommand,
    CreateDatasetCommand,
    CreateDatasetVersionCommand,
    CreateImprovementCandidateCommand,
    CreateRunCommand,
    DeprecateDatasetCommand,
    PauseCanaryCommand,
    PromoteCandidateCommand,
    PublishDatasetCommand,
    RecordCandidateBenchmarkCommand,
    RejectCandidateCommand,
    RejectDatasetReviewCommand,
    RequestCandidateApprovalCommand,
    RequestCanaryRollbackCommand,
    RollbackPromotedCandidateCommand,
    SkipCaseCommand,
    StartCanaryCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.views import (
    CanaryPromotionDecisionView,
    DatasetView,
    FailureClusterView,
    ImprovementCandidateView,
    OnlineEvaluationSampleView,
    RegressionReportView,
    RunView,
    ScoreView,
    TestCaseView,
)
from evaluationimprovement.domain.enums import CandidateType, Criticality, RiskLevel
from evaluationimprovement.domain.ids import CandidateId, DatasetId, IdempotencyKey, RunId, TestCaseId
from evaluationimprovement.interfaces.rest.schemas import (
    AddTestCasesRequest,
    AdvanceCanaryRequest,
    ApproveCandidateRequest,
    ArchiveDatasetRequest,
    CanaryPromotionDecisionResponse,
    CancelRunRequest,
    CollectOnlineSampleRequest,
    CompleteCanaryRollbackRequest,
    CreateDatasetRequest,
    CreateDatasetVersionRequest,
    CreateImprovementCandidateRequest,
    CreateRunRequest,
    DatasetResponse,
    DeprecateDatasetRequest,
    EvidenceRefResponse,
    FailureClusterResponse,
    ImprovementCandidateResponse,
    OnlineEvaluationSampleResponse,
    PauseCanaryRequest,
    PromoteCandidateRequest,
    PublishDatasetRequest,
    RecordCandidateBenchmarkRequest,
    RegressionReportResponse,
    RejectCandidateRequest,
    RejectDatasetReviewRequest,
    RequestCandidateApprovalRequest,
    RollbackCandidateRequest,
    RollbackPromotedCandidateRequest,
    RunResponse,
    ScoreResponse,
    SkipCaseRequest,
    StartCanaryRequest,
    SubmitDatasetForReviewRequest,
    TestCaseResponse,
)


def to_create_dataset_command(request: CreateDatasetRequest, actor: str, tenant_id: str) -> CreateDatasetCommand:
    return CreateDatasetCommand(
        name=request.name, version=request.version, domain=request.domain, scenario_tags=tuple(request.scenario_tags),
        created_by=request.created_by, actor=actor, correlation_id=request.correlation_id,
        lineage_parent_id=DatasetId(request.lineage_parent_id) if request.lineage_parent_id else None,
        tenant_id=tenant_id,
    )


def to_add_test_cases_command(dataset_id: DatasetId, request: AddTestCasesRequest, actor: str, tenant_id: str) -> AddTestCasesCommand:
    cases = tuple(
        TestCaseInput(
            case_key=c.case_key, scenario=c.scenario, user_request_redacted=c.user_request_redacted,
            mock_system_state=c.mock_system_state, ground_truth=c.ground_truth, allowed_tools=tuple(c.allowed_tools),
            forbidden_tools=tuple(c.forbidden_tools), required_approval=c.required_approval,
            verification_condition=c.verification_condition, criticality=Criticality[c.criticality],
        )
        for c in request.cases
    )
    return AddTestCasesCommand(dataset_id=dataset_id, cases=cases, actor=actor, correlation_id=request.correlation_id, tenant_id=tenant_id)


def to_publish_dataset_command(dataset_id: DatasetId, request: PublishDatasetRequest, actor: str, tenant_id: str) -> PublishDatasetCommand:
    return PublishDatasetCommand(
        dataset_id=dataset_id, published_by=request.published_by, actor=actor, correlation_id=request.correlation_id,
        tenant_id=tenant_id,
    )


def to_submit_for_review_command(
    dataset_id: DatasetId, request: SubmitDatasetForReviewRequest, actor: str, tenant_id: str,
) -> SubmitDatasetForReviewCommand:
    return SubmitDatasetForReviewCommand(dataset_id=dataset_id, actor=actor, correlation_id=request.correlation_id, tenant_id=tenant_id)


def to_reject_review_command(
    dataset_id: DatasetId, request: RejectDatasetReviewRequest, actor: str, tenant_id: str,
) -> RejectDatasetReviewCommand:
    return RejectDatasetReviewCommand(
        dataset_id=dataset_id, reason=request.reason, actor=actor, correlation_id=request.correlation_id, tenant_id=tenant_id,
    )


def to_deprecate_dataset_command(dataset_id: DatasetId, request: DeprecateDatasetRequest, actor: str, tenant_id: str) -> DeprecateDatasetCommand:
    return DeprecateDatasetCommand(dataset_id=dataset_id, actor=actor, correlation_id=request.correlation_id, tenant_id=tenant_id)


def to_archive_dataset_command(dataset_id: DatasetId, request: ArchiveDatasetRequest, actor: str, tenant_id: str) -> ArchiveDatasetCommand:
    return ArchiveDatasetCommand(dataset_id=dataset_id, actor=actor, correlation_id=request.correlation_id, tenant_id=tenant_id)


def to_create_dataset_version_command(
    dataset_id: DatasetId, request: CreateDatasetVersionRequest, actor: str, tenant_id: str,
) -> CreateDatasetVersionCommand:
    return CreateDatasetVersionCommand(
        parent_dataset_id=dataset_id, new_version=request.new_version, created_by=request.created_by, actor=actor,
        correlation_id=request.correlation_id, tenant_id=tenant_id,
    )


def to_create_run_command(request: CreateRunRequest, actor: str) -> CreateRunCommand:
    return CreateRunCommand(
        run_key=request.run_key, dataset_id=DatasetId(request.dataset_id), target_version=request.target_version,
        baseline_version=request.baseline_version, grader_bundle_version=request.grader_bundle_version,
        policy_version=request.policy_version, gate_policy=request.gate_policy, triggered_by=request.triggered_by,
        actor=actor, correlation_id=request.correlation_id,
    )


def to_cancel_run_command(run_id: RunId, request: CancelRunRequest, actor: str) -> CancelRunCommand:
    return CancelRunCommand(run_id=run_id, reason=request.reason, actor=actor, correlation_id=request.correlation_id)


def to_skip_case_command(run_id: RunId, test_case_id: TestCaseId, request: SkipCaseRequest, actor: str) -> SkipCaseCommand:
    return SkipCaseCommand(
        run_id=run_id, test_case_id=test_case_id, reason=request.reason, actor=actor, correlation_id=request.correlation_id,
    )


def to_create_candidate_command(request: CreateImprovementCandidateRequest, actor: str) -> CreateImprovementCandidateCommand:
    return CreateImprovementCandidateCommand(
        candidate_type=CandidateType[request.candidate_type], source_run_id=RunId(request.source_run_id),
        source_failure_cluster_id=request.source_failure_cluster_id, target_component=request.target_component,
        proposed_change=request.proposed_change, risk_level=RiskLevel[request.risk_level], created_by=request.created_by,
        actor=actor, correlation_id=request.correlation_id, idempotency_key=IdempotencyKey(request.idempotency_key),
    )


def to_record_benchmark_command(candidate_id: CandidateId, request: RecordCandidateBenchmarkRequest, actor: str) -> RecordCandidateBenchmarkCommand:
    return RecordCandidateBenchmarkCommand(
        candidate_id=candidate_id, benchmark_run_id=RunId(request.benchmark_run_id), actor=actor,
        correlation_id=request.correlation_id,
    )


def to_request_approval_command(candidate_id: CandidateId, request: RequestCandidateApprovalRequest, actor: str) -> RequestCandidateApprovalCommand:
    return RequestCandidateApprovalCommand(candidate_id=candidate_id, actor=actor, correlation_id=request.correlation_id)


def to_approve_candidate_command(candidate_id: CandidateId, request: ApproveCandidateRequest, actor: str) -> ApproveCandidateCommand:
    return ApproveCandidateCommand(candidate_id=candidate_id, approved_by=request.approved_by, actor=actor, correlation_id=request.correlation_id)


def to_reject_candidate_command(candidate_id: CandidateId, request: RejectCandidateRequest, actor: str) -> RejectCandidateCommand:
    return RejectCandidateCommand(candidate_id=candidate_id, reason=request.reason, actor=actor, correlation_id=request.correlation_id)


def to_start_canary_command(candidate_id: CandidateId, request: StartCanaryRequest, actor: str) -> StartCanaryCommand:
    stages = tuple(
        CanaryStageInput(s.traffic_percent, s.min_duration_minutes, s.rollback_error_rate_threshold, s.sample_size)
        for s in request.stages
    )
    return StartCanaryCommand(
        candidate_id=candidate_id, plan_version=request.plan_version, stages=stages, actor=actor,
        correlation_id=request.correlation_id, idempotency_key=IdempotencyKey(request.idempotency_key),
    )


def to_request_canary_rollback_command(candidate_id: CandidateId, request: RollbackCandidateRequest, actor: str) -> RequestCanaryRollbackCommand:
    return RequestCanaryRollbackCommand(
        candidate_id=candidate_id, reason=request.reason, actor=actor, correlation_id=request.correlation_id,
        idempotency_key=IdempotencyKey(request.idempotency_key),
    )


def to_advance_canary_command(candidate_id: CandidateId, request: AdvanceCanaryRequest, actor: str) -> AdvanceCanaryCommand:
    return AdvanceCanaryCommand(
        candidate_id=candidate_id, actor=actor, correlation_id=request.correlation_id,
        idempotency_key=IdempotencyKey(request.idempotency_key),
    )


def to_pause_canary_command(candidate_id: CandidateId, request: PauseCanaryRequest, actor: str) -> PauseCanaryCommand:
    return PauseCanaryCommand(
        candidate_id=candidate_id, reason=request.reason, actor=actor, correlation_id=request.correlation_id,
        idempotency_key=IdempotencyKey(request.idempotency_key),
    )


def to_complete_canary_rollback_command(candidate_id: CandidateId, request: CompleteCanaryRollbackRequest, actor: str) -> CompleteCanaryRollbackCommand:
    return CompleteCanaryRollbackCommand(candidate_id=candidate_id, actor=actor, correlation_id=request.correlation_id)


def to_promote_candidate_command(candidate_id: CandidateId, request: PromoteCandidateRequest, actor: str) -> PromoteCandidateCommand:
    return PromoteCandidateCommand(
        candidate_id=candidate_id, promoted_version=request.promoted_version, actor=actor, correlation_id=request.correlation_id,
    )


def to_rollback_promoted_candidate_command(candidate_id: CandidateId, request: RollbackPromotedCandidateRequest, actor: str) -> RollbackPromotedCandidateCommand:
    return RollbackPromotedCandidateCommand(
        candidate_id=candidate_id, reason=request.reason, actor=actor, correlation_id=request.correlation_id,
        idempotency_key=IdempotencyKey(request.idempotency_key),
    )


def to_dataset_response(view: DatasetView) -> DatasetResponse:
    return DatasetResponse(
        dataset_id=view.dataset_id.value, name=view.name, version=view.version, domain=view.domain, status=view.status.value,
        case_count=view.case_count, created_by=view.created_by, published_by=view.published_by, created_at=view.created_at,
        published_at=view.published_at, content_hash=view.content_hash, tenant_id=view.tenant_id,
    )


def to_test_case_response(view: TestCaseView) -> TestCaseResponse:
    return TestCaseResponse(
        test_case_id=view.test_case_id.value, dataset_id=view.dataset_id.value, case_key=view.case_key,
        scenario=view.scenario, user_request_redacted=view.user_request_redacted, mock_system_state=view.mock_system_state,
        ground_truth=view.ground_truth, allowed_tools=list(view.allowed_tools), forbidden_tools=list(view.forbidden_tools),
        required_approval=view.required_approval, verification_condition=view.verification_condition,
        criticality=view.criticality, input_hash=view.input_hash,
    )


def to_run_response(view: RunView) -> RunResponse:
    return RunResponse(
        run_id=view.run_id.value, run_key=view.run_key, dataset_id=view.dataset_id.value, dataset_version=view.dataset_version,
        target_version=view.target_version, baseline_version=view.baseline_version, status=view.status.value,
        triggered_by=view.triggered_by, started_at=view.started_at, completed_at=view.completed_at,
    )


def to_score_response(view: ScoreView) -> ScoreResponse:
    evidence = (
        EvidenceRefResponse(
            artifact_provider=view.evidence_ref.artifact_provider, artifact_uri=view.evidence_ref.artifact_uri,
            artifact_hash=view.evidence_ref.artifact_hash, retention_until=view.evidence_ref.retention_until,
        )
        if view.evidence_ref else None
    )
    return ScoreResponse(
        score_id=view.score_id.value, run_id=view.run_id.value, test_case_id=view.test_case_id.value,
        dimension=view.dimension.value, score=view.score, passed=view.passed, grader_type=view.grader_type.value,
        grader_version=view.grader_version, failure_code=view.failure_code.value if view.failure_code else None,
        evidence=evidence, details=view.details,
    )


def to_failure_cluster_response(view: FailureClusterView) -> FailureClusterResponse:
    return FailureClusterResponse(
        cluster_id=view.cluster_id, run_id=view.run_id.value, dimension=view.dimension,
        failure_code=view.failure_code.value, case_count=view.case_count,
        test_case_ids=[tc.value for tc in view.test_case_ids],
    )


def to_collect_online_sample_command(request: CollectOnlineSampleRequest, actor: str) -> CollectOnlineSampleCommand:
    return CollectOnlineSampleCommand(
        candidate_id=CandidateId(request.candidate_id) if request.candidate_id else None,
        target_version=request.target_version, source_event_type=request.source_event_type,
        source_trace_ref=request.source_trace_ref, redacted_context=request.redacted_context, actor=actor,
        correlation_id=request.correlation_id,
    )


def to_online_sample_response(view: OnlineEvaluationSampleView) -> OnlineEvaluationSampleResponse:
    return OnlineEvaluationSampleResponse(
        sample_id=view.sample_id, candidate_id=view.candidate_id.value if view.candidate_id else None,
        target_version=view.target_version, source_event_type=view.source_event_type,
        source_trace_ref=view.source_trace_ref, status=view.status.value, collected_at=view.collected_at,
        scored_at=view.scored_at, composite_score=view.composite_score,
        failure_code=view.failure_code.value if view.failure_code else None,
    )


def to_canary_promotion_decision_response(view: CanaryPromotionDecisionView) -> CanaryPromotionDecisionResponse:
    return CanaryPromotionDecisionResponse(
        candidate_id=view.candidate_id.value, eligible_to_advance=view.eligible_to_advance,
        recommend_rollback=view.recommend_rollback, sample_count=view.sample_count,
        required_sample_size=view.required_sample_size, error_rate=view.error_rate, reason=view.reason,
    )


def to_regression_report_response(view: RegressionReportView) -> RegressionReportResponse:
    return RegressionReportResponse(
        report_id=view.report_id.value, run_id=view.run_id.value,
        baseline_run_id=view.baseline_run_id.value if view.baseline_run_id else None, overall_decision=view.overall_decision.value,
        critical_failures=list(view.critical_failures), recommendation=view.recommendation, created_at=view.created_at,
    )


def to_candidate_response(view: ImprovementCandidateView) -> ImprovementCandidateResponse:
    return ImprovementCandidateResponse(
        candidate_id=view.candidate_id.value, candidate_type=view.candidate_type.value, source_run_id=view.source_run_id.value,
        target_component=view.target_component, risk_level=view.risk_level.value, status=view.status.value,
        created_by=view.created_by, benchmark_run_id=view.benchmark_run_id.value if view.benchmark_run_id else None,
        benchmark_passed=view.benchmark_passed, approved_by=view.approved_by, approval_request_id=view.approval_request_id,
        canary_status=view.canary_status.value if view.canary_status else None, promoted_version=view.promoted_version,
        created_at=view.created_at, updated_at=view.updated_at,
    )
