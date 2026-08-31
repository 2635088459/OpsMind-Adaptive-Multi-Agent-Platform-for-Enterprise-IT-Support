"""13-package-and-class-design §"Interfaces": REST controller for 05-api-contracts's
Dataset/Run/Report/Candidate API. Depends only on application.ports_in Protocols;
never touches a repository or infrastructure directly. 05-api-contracts §"API 原则":
"所有写 API 必须要求 01 提供的 service identity 或 evaluator/admin role，并写入 audit" — real
JWT/claims integration with 01-user-access-authentication is a future cross-domain-
contracts spec (mirrors phase-07); `_actor()`/`_require_role()` below read a
caller-asserted `X-Actor-Id`/`X-Actor-Role` header pair as this spec's own honest
placeholder for that trusted identity, the same "trusted caller asserts" precedent
several sibling domains' own phase-00 specs used before their own JWT integration
landed.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, Query, status

from evaluationimprovement.application.commands import (
    CompareRegressionCommand,
    EvaluateReleaseGateCommand,
    ExecuteCaseCommand,
    FinalizeRunScoringCommand,
    ScoreCaseCommand,
)
from evaluationimprovement.application.ports_in import (
    CandidateQueryUseCase,
    CanaryPromotionUseCase,
    CompareRegressionUseCase,
    CreateDatasetUseCase,
    CreateImprovementCandidateUseCase,
    CreateRunUseCase,
    DatasetQueryUseCase,
    EvaluateReleaseGateUseCase,
    ExecuteCaseUseCase,
    FailureClusterQueryUseCase,
    ManageCanaryUseCase,
    OnlineSampleUseCase,
    PublishDatasetUseCase,
    ReportQueryUseCase,
    RunQueryUseCase,
    ScoreRunUseCase,
)
from evaluationimprovement.container import (
    get_candidate_query_port,
    get_canary_promotion_port,
    get_compare_regression_port,
    get_create_dataset_port,
    get_create_improvement_candidate_port,
    get_create_run_port,
    get_dataset_query_port,
    get_evaluate_release_gate_port,
    get_execute_case_port,
    get_failure_cluster_query_port,
    get_manage_canary_port,
    get_online_sample_port,
    get_publish_dataset_port,
    get_report_query_port,
    get_run_query_port,
    get_score_run_port,
)
from evaluationimprovement.domain.ids import CandidateId, DatasetId, RunId, TestCaseId
from evaluationimprovement.interfaces.rest.mapper import (
    to_add_test_cases_command,
    to_advance_canary_command,
    to_approve_candidate_command,
    to_archive_dataset_command,
    to_candidate_response,
    to_cancel_run_command,
    to_complete_canary_rollback_command,
    to_create_candidate_command,
    to_create_dataset_command,
    to_create_dataset_version_command,
    to_create_run_command,
    to_dataset_response,
    to_canary_promotion_decision_response,
    to_collect_online_sample_command,
    to_deprecate_dataset_command,
    to_failure_cluster_response,
    to_online_sample_response,
    to_pause_canary_command,
    to_promote_candidate_command,
    to_publish_dataset_command,
    to_record_benchmark_command,
    to_regression_report_response,
    to_reject_candidate_command,
    to_reject_review_command,
    to_request_approval_command,
    to_request_canary_rollback_command,
    to_rollback_promoted_candidate_command,
    to_run_response,
    to_score_response,
    to_skip_case_command,
    to_start_canary_command,
    to_submit_for_review_command,
    to_test_case_response,
)
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
from evaluationimprovement.interfaces.security import optional_actor as _optional_actor
from evaluationimprovement.interfaces.security import require_role as _require_role
from evaluationimprovement.interfaces.security import tenant_id as _tenant_id

router = APIRouter(prefix="/evaluation", tags=["evaluation"])


# Dataset API ------------------------------------------------------------------------
@router.post("/datasets", response_model=DatasetResponse, status_code=status.HTTP_201_CREATED)
def create_dataset(
    request: CreateDatasetRequest, actor: str = Depends(_require_role("create_dataset")), tenant: str = Depends(_tenant_id),
    port: CreateDatasetUseCase = Depends(get_create_dataset_port),
) -> DatasetResponse:
    return to_dataset_response(port.create_dataset(to_create_dataset_command(request, actor, tenant)))


@router.post("/datasets/{dataset_id}/cases", response_model=list[TestCaseResponse])
def add_test_cases(
    dataset_id: UUID, request: AddTestCasesRequest, actor: str = Depends(_require_role("create_dataset")),
    tenant: str = Depends(_tenant_id), port: CreateDatasetUseCase = Depends(get_create_dataset_port),
) -> list[TestCaseResponse]:
    views = port.add_test_cases(to_add_test_cases_command(DatasetId(dataset_id), request, actor, tenant))
    return [to_test_case_response(v) for v in views]


@router.post("/datasets/{dataset_id}/publish", response_model=DatasetResponse)
def publish_dataset(
    dataset_id: UUID, request: PublishDatasetRequest, actor: str = Depends(_require_role("publish_dataset")),
    tenant: str = Depends(_tenant_id), port: PublishDatasetUseCase = Depends(get_publish_dataset_port),
) -> DatasetResponse:
    return to_dataset_response(port.publish(to_publish_dataset_command(DatasetId(dataset_id), request, actor, tenant)))


@router.post("/datasets/{dataset_id}/submit-review", response_model=DatasetResponse)
def submit_dataset_for_review(
    dataset_id: UUID, request: SubmitDatasetForReviewRequest, actor: str = Depends(_require_role("create_dataset")),
    tenant: str = Depends(_tenant_id), port: PublishDatasetUseCase = Depends(get_publish_dataset_port),
) -> DatasetResponse:
    return to_dataset_response(port.submit_for_review(to_submit_for_review_command(DatasetId(dataset_id), request, actor, tenant)))


@router.post("/datasets/{dataset_id}/reject-review", response_model=DatasetResponse)
def reject_dataset_review(
    dataset_id: UUID, request: RejectDatasetReviewRequest, actor: str = Depends(_require_role("publish_dataset")),
    tenant: str = Depends(_tenant_id), port: PublishDatasetUseCase = Depends(get_publish_dataset_port),
) -> DatasetResponse:
    return to_dataset_response(port.reject_review(to_reject_review_command(DatasetId(dataset_id), request, actor, tenant)))


@router.post("/datasets/{dataset_id}/deprecate", response_model=DatasetResponse)
def deprecate_dataset(
    dataset_id: UUID, request: DeprecateDatasetRequest, actor: str = Depends(_require_role("publish_dataset")),
    tenant: str = Depends(_tenant_id), port: PublishDatasetUseCase = Depends(get_publish_dataset_port),
) -> DatasetResponse:
    return to_dataset_response(port.deprecate(to_deprecate_dataset_command(DatasetId(dataset_id), request, actor, tenant)))


@router.post("/datasets/{dataset_id}/archive", response_model=DatasetResponse)
def archive_dataset(
    dataset_id: UUID, request: ArchiveDatasetRequest, actor: str = Depends(_require_role("publish_dataset")),
    tenant: str = Depends(_tenant_id), port: PublishDatasetUseCase = Depends(get_publish_dataset_port),
) -> DatasetResponse:
    return to_dataset_response(port.archive(to_archive_dataset_command(DatasetId(dataset_id), request, actor, tenant)))


@router.post("/datasets/{dataset_id}/versions", response_model=DatasetResponse, status_code=status.HTTP_201_CREATED)
def create_dataset_version(
    dataset_id: UUID, request: CreateDatasetVersionRequest, actor: str = Depends(_require_role("create_dataset")),
    tenant: str = Depends(_tenant_id), port: CreateDatasetUseCase = Depends(get_create_dataset_port),
) -> DatasetResponse:
    """SPEC-EI-004 / 02-business-invariants INV-EI-005: creates a new DRAFT dataset
    version from an already-PUBLISHED parent, with the parent's own test cases
    copied forward as a starting point.
    """
    return to_dataset_response(port.create_next_version(to_create_dataset_version_command(DatasetId(dataset_id), request, actor, tenant)))


@router.get("/datasets/{dataset_id}/cases", response_model=list[TestCaseResponse])
def find_test_cases(
    dataset_id: UUID, actor: str = Depends(_require_role("view_evaluation_data")),  # noqa: ARG001 (role check only; no audit requirement for reads)
    tenant: str = Depends(_tenant_id), port: DatasetQueryUseCase = Depends(get_dataset_query_port),
) -> list[TestCaseResponse]:
    """SPEC-EI-005: the full test case schema, not just the truncated
    create-response shape. SPEC-EI-008: requires an authenticated evaluation role and
    is scoped to the caller's own tenant."""
    return [to_test_case_response(v) for v in port.find_test_cases(DatasetId(dataset_id), tenant)]


@router.get("/datasets/{dataset_id}/cases/{test_case_id}", response_model=TestCaseResponse)
def find_test_case(
    dataset_id: UUID, test_case_id: UUID, actor: str = Depends(_require_role("view_evaluation_data")),  # noqa: ARG001 (dataset_id scopes the URL; the lookup itself is by test_case_id; actor unused beyond the role check)
    tenant: str = Depends(_tenant_id), port: DatasetQueryUseCase = Depends(get_dataset_query_port),
) -> TestCaseResponse:
    return to_test_case_response(port.find_test_case(TestCaseId(test_case_id), tenant))


@router.get("/datasets/{dataset_id}", response_model=DatasetResponse)
def find_dataset(
    dataset_id: UUID, actor: str = Depends(_require_role("view_evaluation_data")),  # noqa: ARG001
    tenant: str = Depends(_tenant_id), port: DatasetQueryUseCase = Depends(get_dataset_query_port),
) -> DatasetResponse:
    return to_dataset_response(port.find_dataset(DatasetId(dataset_id), tenant))


@router.get("/datasets", response_model=list[DatasetResponse])
def list_datasets(
    domain: str | None = Query(default=None), status_filter: str | None = Query(default=None, alias="status"),
    name: str | None = Query(default=None), limit: int = Query(default=50, ge=1, le=200),
    actor: str = Depends(_require_role("view_evaluation_data")),  # noqa: ARG001
    tenant: str = Depends(_tenant_id), port: DatasetQueryUseCase = Depends(get_dataset_query_port),
) -> list[DatasetResponse]:
    """SPEC-EI-004: `?name=` returns the full lineage chain for that dataset name
    (any status, oldest first) instead of the default PUBLISHED-only listing —
    05-api-contracts's own query surface never named a distinct path for this, and a
    query-param branch here avoids a route-ordering ambiguity with
    `/datasets/{dataset_id}` a separate `/datasets/versions` path would introduce.
    SPEC-EI-008: both branches are scoped to the caller's own tenant.
    """
    if name is not None:
        return [to_dataset_response(v) for v in port.find_versions(name, tenant)]
    return [to_dataset_response(v) for v in port.list_datasets(domain, status_filter, tenant, limit)]


# Run API --------------------------------------------------------------------------
@router.post("/runs", response_model=RunResponse, status_code=status.HTTP_201_CREATED)
def create_run(
    request: CreateRunRequest, actor: str = Depends(_require_role("create_run")), port: CreateRunUseCase = Depends(get_create_run_port),
) -> RunResponse:
    return to_run_response(port.create_run(to_create_run_command(request, actor)))


@router.get("/runs", response_model=list[RunResponse])
def list_runs(
    dataset_id: UUID = Query(...), status_filter: str | None = Query(default=None, alias="status"),
    limit: int = Query(default=50, ge=1, le=200), port: RunQueryUseCase = Depends(get_run_query_port),
) -> list[RunResponse]:
    """SPEC-EI-010 / 05-api-contracts: "状态可见性" — every run against a dataset,
    newest first, optionally narrowed to one status.
    """
    return [to_run_response(v) for v in port.list_runs(DatasetId(dataset_id), status_filter, limit)]


@router.get("/runs/stuck", response_model=list[RunResponse])
def find_stuck_runs(
    sla_seconds: int = Query(default=3600, ge=1), actor: str = Depends(_require_role("manage_gate_policy")),
    port: RunQueryUseCase = Depends(get_run_query_port),
) -> list[RunResponse]:
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 12-observability
    §"Alerts": "run stuck in RUNNING 或 SCORING beyond SLA." Registered before
    `/runs/{run_id}` — FastAPI resolves routes in registration order within one
    router, and `{run_id}` is a UUID path param that would otherwise 422 on the
    literal segment "stuck" rather than falling through to this route.
    """
    return [to_run_response(v) for v in port.find_stuck_runs(sla_seconds)]


@router.get("/runs/{run_id}", response_model=RunResponse)
def find_run(run_id: UUID, port: RunQueryUseCase = Depends(get_run_query_port)) -> RunResponse:
    return to_run_response(port.find_run(RunId(run_id)))


@router.get("/runs/{run_id}/scores", response_model=list[ScoreResponse])
def find_scores(
    run_id: UUID, caller: tuple[str, str] = Depends(_optional_actor), port: RunQueryUseCase = Depends(get_run_query_port),
) -> list[ScoreResponse]:
    """SPEC-EI-034 (evaluation-security-redaction-observability): a caller who never
    asserts an identity still reads aggregate scores (05-api-contracts's own default
    read floor) — only case-level evidence visibility depends on the resolved role.
    """
    actor_id, actor_role = caller
    return [to_score_response(v) for v in port.find_scores(RunId(run_id), actor_id, actor_role)]


@router.get("/runs/{run_id}/failure-clusters", response_model=list[FailureClusterResponse])
def list_failure_clusters(
    run_id: UUID, port: FailureClusterQueryUseCase = Depends(get_failure_cluster_query_port),
) -> list[FailureClusterResponse]:
    """SPEC-EI-023 (failure-clustering-root-cause-taxonomy): the `(dimension,
    failure_code)` root-cause taxonomy for a run's own failed scores, derived at
    query time — see application.services.cluster_run_failures's own docstring.
    """
    return [to_failure_cluster_response(v) for v in port.list_clusters(RunId(run_id))]


@router.post("/runs/{run_id}/cancel", response_model=RunResponse)
def cancel_run(
    run_id: UUID, request: CancelRunRequest, actor: str = Depends(_require_role("cancel_run")),
    port: CreateRunUseCase = Depends(get_create_run_port),
) -> RunResponse:
    return to_run_response(port.cancel_run(to_cancel_run_command(RunId(run_id), request, actor)))


# Pipeline steps ---------------------------------------------------------------------
# Not named in 05-api-contracts's own minimal Run API list (that doc's own preamble:
# "本 spec 可能新增或修改 07 API"). No worker process constructs
# ExecuteCaseService/ScoreRunService/CompareRegressionService/
# EvaluateReleaseGateService as a running consumer in this spec's own scope (mirrors
# tool-integration-gateway's own SPEC-TG-001 precedent for worker classes); these
# admin-triggered endpoints are the seam a future runner/worker spec will call instead
# of a human, and are what tests/test_app.py's own end-to-end walkthrough drives today.
@router.post("/runs/{run_id}/cases/{test_case_id}/execute", status_code=status.HTTP_202_ACCEPTED)
def execute_case(
    run_id: UUID, test_case_id: UUID, correlation_id: str = Query(...),
    actor: str = Depends(_require_role("create_run")), port: ExecuteCaseUseCase = Depends(get_execute_case_port),
) -> dict[str, str]:
    port.execute_case(ExecuteCaseCommand(run_id=RunId(run_id), test_case_id=TestCaseId(test_case_id), attempt=1, actor=actor, correlation_id=correlation_id))
    return {"status": "executed"}


@router.post("/runs/{run_id}/cases/{test_case_id}/skip", status_code=status.HTTP_202_ACCEPTED)
def skip_case(
    run_id: UUID, test_case_id: UUID, request: SkipCaseRequest,
    actor: str = Depends(_require_role("create_run")), port: ExecuteCaseUseCase = Depends(get_execute_case_port),
) -> dict[str, str]:
    """SPEC-EI-009: marks a case SKIPPED instead of leaving it permanently
    unaccounted-for — same admin-triggered seam as execute_case/score_case above."""
    port.skip_case(to_skip_case_command(RunId(run_id), TestCaseId(test_case_id), request, actor))
    return {"status": "skipped"}


@router.post("/runs/{run_id}/cases/{test_case_id}/score", response_model=list[ScoreResponse])
def score_case(
    run_id: UUID, test_case_id: UUID, run_generation: int = Query(default=1), correlation_id: str = Query(...),
    actor: str = Depends(_require_role("create_run")), port: ScoreRunUseCase = Depends(get_score_run_port),
) -> list[ScoreResponse]:
    views = port.score_case(ScoreCaseCommand(run_id=RunId(run_id), test_case_id=TestCaseId(test_case_id), run_generation=run_generation, actor=actor, correlation_id=correlation_id))
    return [to_score_response(v) for v in views]


@router.post("/runs/{run_id}/finalize-scoring", response_model=RunResponse)
def finalize_scoring(
    run_id: UUID, correlation_id: str = Query(...), actor: str = Depends(_require_role("create_run")),
    port: ScoreRunUseCase = Depends(get_score_run_port),
) -> RunResponse:
    return to_run_response(port.finalize_scoring(FinalizeRunScoringCommand(run_id=RunId(run_id), actor=actor, correlation_id=correlation_id)))


@router.post("/runs/{run_id}/compare", response_model=RegressionReportResponse)
def compare_regression(
    run_id: UUID, baseline_run_id: UUID | None = Query(default=None), correlation_id: str = Query(...),
    actor: str = Depends(_require_role("create_run")), port: CompareRegressionUseCase = Depends(get_compare_regression_port),
) -> RegressionReportResponse:
    command = CompareRegressionCommand(run_id=RunId(run_id), baseline_run_id=RunId(baseline_run_id) if baseline_run_id else None, actor=actor, correlation_id=correlation_id)
    return to_regression_report_response(port.compare(command))


@router.post("/runs/{run_id}/evaluate-gate", response_model=RegressionReportResponse)
def evaluate_gate(
    run_id: UUID, gate_policy: str = Query(...), correlation_id: str = Query(...),
    actor: str = Depends(_require_role("create_run")), port: EvaluateReleaseGateUseCase = Depends(get_evaluate_release_gate_port),
) -> RegressionReportResponse:
    command = EvaluateReleaseGateCommand(run_id=RunId(run_id), gate_policy=gate_policy, actor=actor, correlation_id=correlation_id)
    return to_regression_report_response(port.evaluate(command))


# Report API --------------------------------------------------------------------------
@router.get("/runs/{run_id}/regression-report", response_model=RegressionReportResponse)
def find_report_for_run(run_id: UUID, port: ReportQueryUseCase = Depends(get_report_query_port)) -> RegressionReportResponse:
    return to_regression_report_response(port.find_report_for_run(RunId(run_id)))


# Candidate API ------------------------------------------------------------------------
@router.post("/improvement-candidates", response_model=ImprovementCandidateResponse, status_code=status.HTTP_201_CREATED)
def create_candidate(
    request: CreateImprovementCandidateRequest, actor: str = Depends(_require_role("create_candidate")),
    port: CreateImprovementCandidateUseCase = Depends(get_create_improvement_candidate_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.create(to_create_candidate_command(request, actor)))


@router.get("/improvement-candidates/{candidate_id}", response_model=ImprovementCandidateResponse)
def find_candidate(candidate_id: UUID, port: CandidateQueryUseCase = Depends(get_candidate_query_port)) -> ImprovementCandidateResponse:
    return to_candidate_response(port.find_candidate(CandidateId(candidate_id)))


@router.post("/improvement-candidates/{candidate_id}/benchmark", response_model=ImprovementCandidateResponse)
def record_candidate_benchmark(
    candidate_id: UUID, request: RecordCandidateBenchmarkRequest, actor: str = Depends(_require_role("create_candidate")),
    port: CreateImprovementCandidateUseCase = Depends(get_create_improvement_candidate_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.record_benchmark(to_record_benchmark_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/request-approval", response_model=ImprovementCandidateResponse)
def request_candidate_approval(
    candidate_id: UUID, request: RequestCandidateApprovalRequest, actor: str = Depends(_require_role("create_candidate")),
    port: CreateImprovementCandidateUseCase = Depends(get_create_improvement_candidate_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.request_approval(to_request_approval_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/approve", response_model=ImprovementCandidateResponse)
def approve_candidate(
    candidate_id: UUID, request: ApproveCandidateRequest, actor: str = Depends(_require_role("approve_candidate")),
    port: CreateImprovementCandidateUseCase = Depends(get_create_improvement_candidate_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.approve(to_approve_candidate_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/reject", response_model=ImprovementCandidateResponse)
def reject_candidate(
    candidate_id: UUID, request: RejectCandidateRequest, actor: str = Depends(_require_role("create_candidate")),
    port: CreateImprovementCandidateUseCase = Depends(get_create_improvement_candidate_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.reject(to_reject_candidate_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/start-canary", response_model=ImprovementCandidateResponse)
def start_canary(
    candidate_id: UUID, request: StartCanaryRequest, actor: str = Depends(_require_role("manage_canary")),
    port: ManageCanaryUseCase = Depends(get_manage_canary_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.start_canary(to_start_canary_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/rollback", response_model=ImprovementCandidateResponse)
def request_candidate_rollback(
    candidate_id: UUID, request: RollbackCandidateRequest, actor: str = Depends(_require_role("manage_canary")),
    port: ManageCanaryUseCase = Depends(get_manage_canary_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.request_rollback(to_request_canary_rollback_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/advance-canary", response_model=ImprovementCandidateResponse)
def advance_canary(
    candidate_id: UUID, request: AdvanceCanaryRequest, actor: str = Depends(_require_role("manage_canary")),
    port: ManageCanaryUseCase = Depends(get_manage_canary_port),
) -> ImprovementCandidateResponse:
    """SPEC-EI-036 (evaluation-contract-e2e-harness-final-release): closes the gap
    this phase's own final coverage audit found — see AdvanceCanaryRequest's own
    docstring.
    """
    return to_candidate_response(port.advance(to_advance_canary_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/pause-canary", response_model=ImprovementCandidateResponse)
def pause_canary(
    candidate_id: UUID, request: PauseCanaryRequest, actor: str = Depends(_require_role("manage_canary")),
    port: ManageCanaryUseCase = Depends(get_manage_canary_port),
) -> ImprovementCandidateResponse:
    return to_candidate_response(port.pause(to_pause_canary_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/complete-rollback", response_model=ImprovementCandidateResponse)
def complete_candidate_rollback(
    candidate_id: UUID, request: CompleteCanaryRollbackRequest, actor: str = Depends(_require_role("manage_canary")),
    port: ManageCanaryUseCase = Depends(get_manage_canary_port),
) -> ImprovementCandidateResponse:
    """10-failure-handling §"Candidate Rollback": records that the rollback the
    Runtime/Config owner already executed has completed — see
    ManageCanaryService.complete_rollback()'s own docstring.
    """
    return to_candidate_response(port.complete_rollback(to_complete_canary_rollback_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/promote", response_model=ImprovementCandidateResponse)
def promote_candidate(
    candidate_id: UUID, request: PromoteCandidateRequest, actor: str = Depends(_require_role("manage_canary")),
    port: CreateImprovementCandidateUseCase = Depends(get_create_improvement_candidate_port),
) -> ImprovementCandidateResponse:
    """02-business-invariants INV-EI-002: promotion requires benchmark + release gate
    + 06 approval + Canary SUCCEEDED — enforced in
    CreateImprovementCandidateService.promote() itself. Gated behind the same
    "manage_canary" role start-canary/advance-canary already use — promotion is the
    canary lifecycle's own terminal step, not a separate authorization concern.
    """
    return to_candidate_response(port.promote(to_promote_candidate_command(CandidateId(candidate_id), request, actor)))


@router.post("/improvement-candidates/{candidate_id}/rollback-promoted", response_model=ImprovementCandidateResponse)
def rollback_promoted_candidate(
    candidate_id: UUID, request: RollbackPromotedCandidateRequest, actor: str = Depends(_require_role("manage_canary")),
    port: ManageCanaryUseCase = Depends(get_manage_canary_port),
) -> ImprovementCandidateResponse:
    """SPEC-EI-036 (evaluation-contract-e2e-harness-final-release): the promoted-
    candidate rollback path this phase's own final coverage audit found missing —
    see RollbackPromotedCandidateCommand's own docstring.
    """
    return to_candidate_response(port.rollback_promoted(to_rollback_promoted_candidate_command(CandidateId(candidate_id), request, actor)))


@router.get("/improvement-candidates/{candidate_id}/promotion-criteria", response_model=CanaryPromotionDecisionResponse)
def evaluate_canary_promotion(
    candidate_id: UUID, port: CanaryPromotionUseCase = Depends(get_canary_promotion_port),
) -> CanaryPromotionDecisionResponse:
    """SPEC-EI-029 (promotion-criteria-rollback-request): a recommendation only — the
    caller still drives advance()/request_rollback() itself (07 只请求 rollback，由
    Runtime/Config owner 执行；不得直接执行 rollback).
    """
    return to_canary_promotion_decision_response(port.evaluate(CandidateId(candidate_id)))


# Online Sample API ------------------------------------------------------------------
# SPEC-EI-028 (online-sample-evaluation): the ingestion side only — see
# CollectOnlineSampleCommand's own docstring for why consuming the actual upstream
# events/sampling policy is SPEC-EI-030's own scope (phase-07). Delayed scoring
# (score_pending()) is an operational surface, never REST-exposed — mirrors
# outbox dispatch/case-runner's own precedent (see OnlineSampleScoringPort's own
# docstring).
@router.post("/online-samples", response_model=OnlineEvaluationSampleResponse, status_code=status.HTTP_201_CREATED)
def collect_online_sample(
    request: CollectOnlineSampleRequest, actor: str = Depends(_require_role("collect_online_sample")),
    port: OnlineSampleUseCase = Depends(get_online_sample_port),
) -> OnlineEvaluationSampleResponse:
    return to_online_sample_response(port.collect(to_collect_online_sample_command(request, actor)))
