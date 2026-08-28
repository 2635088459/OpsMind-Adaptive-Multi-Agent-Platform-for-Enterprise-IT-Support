"""Input ports (13-package-and-class-design §"端口"). One typing.Protocol per named
use case; each is implemented directly by the single application service of the
matching name in evaluationimprovement.application.services.
"""

from __future__ import annotations

from typing import Protocol

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    AdvanceCanaryCommand,
    ApproveCandidateCommand,
    ArchiveDatasetCommand,
    CancelRunCommand,
    CompareRegressionCommand,
    CompleteCanaryRollbackCommand,
    CreateDatasetCommand,
    CreateDatasetVersionCommand,
    CreateImprovementCandidateCommand,
    CreateRunCommand,
    DeprecateDatasetCommand,
    EvaluateReleaseGateCommand,
    ExecuteCaseCommand,
    FinalizeRunScoringCommand,
    PauseCanaryCommand,
    PromoteCandidateCommand,
    PublishDatasetCommand,
    RecordCandidateBenchmarkCommand,
    RejectCandidateCommand,
    RejectDatasetReviewCommand,
    RequestCandidateApprovalCommand,
    RequestCanaryRollbackCommand,
    ScoreCaseCommand,
    SkipCaseCommand,
    StartCanaryCommand,
    SubmitDatasetForReviewCommand,
)
from evaluationimprovement.application.records import AuditRecordEntry, GatePolicyConfig
from evaluationimprovement.application.views import (
    DatasetView,
    DispatchReport,
    GraderDescriptor,
    ImprovementCandidateView,
    RegressionReportView,
    RunView,
    ScoreView,
    TestCaseView,
)
from evaluationimprovement.domain.ids import CandidateId, DatasetId, ReportId, RunId, TestCaseId


class CreateDatasetUseCase(Protocol):
    """05-api-contracts: `POST /evaluation/datasets`, `POST
    /evaluation/datasets/{datasetId}/cases`.
    """

    def create_dataset(self, command: CreateDatasetCommand) -> DatasetView: ...

    def add_test_cases(self, command: AddTestCasesCommand) -> tuple[TestCaseView, ...]: ...

    def create_next_version(self, command: CreateDatasetVersionCommand) -> DatasetView:
        """SPEC-EI-004 / 02-business-invariants INV-EI-005: "变更必须创建新 version，并保留
        lineage." 05-api-contracts: `POST /evaluation/datasets/{datasetId}/versions`.
        """
        ...


class DatasetQueryUseCase(Protocol):
    """05-api-contracts: `GET /evaluation/datasets/{datasetId}`, `GET
    /evaluation/datasets?domain=...&status=...`. SPEC-EI-008 / 11-security: every
    method here takes the caller's own asserted `tenant_id` and scopes/hides results
    to it — a dataset (or test case) owned by another tenant reads back as not-found,
    never as a 403 (which would itself leak existence across tenants).
    """

    def find_dataset(self, dataset_id: DatasetId, tenant_id: str) -> DatasetView: ...

    def list_datasets(self, domain: str | None, status: str | None, tenant_id: str, limit: int) -> tuple[DatasetView, ...]: ...

    def find_versions(self, name: str, tenant_id: str) -> tuple[DatasetView, ...]:
        """SPEC-EI-004: the lineage chain for one dataset name, any status, oldest
        first — the read side proving `lineage_parent_id` is a real, queryable chain
        and not just a write-only foreign key.
        """
        ...

    def find_test_case(self, test_case_id: TestCaseId, tenant_id: str) -> TestCaseView:
        """SPEC-EI-005: 05-api-contracts pattern extended to test cases — the full
        schema TestCaseView now carries, not just the truncated create-response shape.
        """
        ...

    def find_test_cases(self, dataset_id: DatasetId, tenant_id: str) -> tuple[TestCaseView, ...]: ...


class PublishDatasetUseCase(Protocol):
    """05-api-contracts: `POST /evaluation/datasets/{datasetId}/publish`. SPEC-EI-004
    adds the rest of the dataset lifecycle beyond publish — 03-state-machine:
    PUBLISHED -> DEPRECATED -> ARCHIVED. SPEC-EI-006 adds the review step itself as a
    distinct, rejectable action: DRAFT -> REVIEWING -> {PUBLISHED | DRAFT}.
    """

    def publish(self, command: PublishDatasetCommand) -> DatasetView:
        """Requires the dataset already be REVIEWING — call submit_for_review()
        first."""
        ...

    def submit_for_review(self, command: SubmitDatasetForReviewCommand) -> DatasetView: ...

    def reject_review(self, command: RejectDatasetReviewCommand) -> DatasetView: ...

    def deprecate(self, command: DeprecateDatasetCommand) -> DatasetView: ...

    def archive(self, command: ArchiveDatasetCommand) -> DatasetView: ...


class CreateRunUseCase(Protocol):
    """05-api-contracts: `POST /evaluation/runs`, `POST /evaluation/runs/{runId}/
    cancel`.
    """

    def create_run(self, command: CreateRunCommand) -> RunView: ...

    def cancel_run(self, command: CancelRunCommand) -> RunView: ...


class RunQueryUseCase(Protocol):
    """05-api-contracts: `GET /evaluation/runs/{runId}`, `GET /evaluation/runs/{runId}/
    scores`.
    """

    def find_run(self, run_id: RunId) -> RunView: ...

    def find_scores(self, run_id: RunId) -> tuple[ScoreView, ...]: ...

    def list_runs(self, dataset_id: DatasetId, status: str | None, limit: int) -> tuple[RunView, ...]:
        """SPEC-EI-010 / 05-api-contracts: "状态可见性" — `GET
        /evaluation/runs?dataset_id=&status=`, newest first.
        """
        ...


class ExecuteCaseUseCase(Protocol):
    """04-use-cases UC-EI-002 step 3: "Runner 调用 Agent Runtime 的 evaluation
    endpoint."
    """

    def execute_case(self, command: ExecuteCaseCommand) -> None: ...

    def skip_case(self, command: SkipCaseCommand) -> None:
        """SPEC-EI-009: explicitly marks a case SKIPPED instead of leaving it
        permanently unaccounted-for — see domain.enums.CaseExecutionStatus's own
        docstring."""
        ...


class ScoreRunUseCase(Protocol):
    """04-use-cases UC-EI-002 step 5: "Grader registry 执行 deterministic grader 与必要的
    LLM Judge."
    """

    def score_case(self, command: ScoreCaseCommand) -> tuple[ScoreView, ...]: ...

    def finalize_scoring(self, command: FinalizeRunScoringCommand) -> RunView:
        """08-transaction-and-outbox §"Run 完成事务": raises IncompleteRunException if
        any expected case still lacks a score.
        """
        ...


class CompareRegressionUseCase(Protocol):
    def compare(self, command: CompareRegressionCommand) -> RegressionReportView: ...


class ReportQueryUseCase(Protocol):
    def find_report(self, report_id: ReportId) -> RegressionReportView: ...

    def find_report_for_run(self, run_id: RunId) -> RegressionReportView: ...


class EvaluateReleaseGateUseCase(Protocol):
    """04-use-cases UC-EI-003: "输出 PASSED 或 FAILED." Finalizes the run to PASSED/
    FAILED as a side effect (08-transaction-and-outbox §"Run 完成事务").
    """

    def evaluate(self, command: EvaluateReleaseGateCommand) -> RegressionReportView: ...


class CreateImprovementCandidateUseCase(Protocol):
    """05-api-contracts: candidate create/benchmark/request-approval/approve/reject/
    promote.
    """

    def create(self, command: CreateImprovementCandidateCommand) -> ImprovementCandidateView: ...

    def record_benchmark(self, command: RecordCandidateBenchmarkCommand) -> ImprovementCandidateView: ...

    def request_approval(self, command: RequestCandidateApprovalCommand) -> ImprovementCandidateView: ...

    def approve(self, command: ApproveCandidateCommand) -> ImprovementCandidateView: ...

    def reject(self, command: RejectCandidateCommand) -> ImprovementCandidateView: ...

    def promote(self, command: PromoteCandidateCommand) -> ImprovementCandidateView: ...


class CandidateQueryUseCase(Protocol):
    def find_candidate(self, candidate_id: CandidateId) -> ImprovementCandidateView: ...


class ManageCanaryUseCase(Protocol):
    """05-api-contracts: `POST /evaluation/improvement-candidates/{candidateId}/
    start-canary`, `.../rollback`.
    """

    def start_canary(self, command: StartCanaryCommand) -> ImprovementCandidateView: ...

    def advance(self, command: AdvanceCanaryCommand) -> ImprovementCandidateView: ...

    def pause(self, command: PauseCanaryCommand) -> ImprovementCandidateView: ...

    def request_rollback(self, command: RequestCanaryRollbackCommand) -> ImprovementCandidateView: ...

    def complete_rollback(self, command: CompleteCanaryRollbackCommand) -> ImprovementCandidateView: ...


class OutboxDispatchPort(Protocol):
    """08-transaction-and-outbox §"Outbox 发布". Not a domain use case, an operational
    surface — mirrors memory-knowledge-service's own OutboxDispatchPort.
    """

    def dispatch_due_events(self, batch_size: int) -> DispatchReport: ...


class AuditQueryUseCase(Protocol):
    """05-api-contracts §"管理 API": `GET /evaluation/audit`."""

    def list_audit_events(self, limit: int) -> list[AuditRecordEntry]: ...


class GatePolicyUseCase(Protocol):
    """05-api-contracts §"管理 API": `GET/PUT /evaluation/gates/{gatePolicy}`."""

    def find_gate_policy(self, gate_policy: str) -> GatePolicyConfig: ...

    def upsert_gate_policy(self, config: GatePolicyConfig, actor: str) -> GatePolicyConfig: ...


class GraderCatalogUseCase(Protocol):
    """05-api-contracts §"管理 API": `GET /evaluation/graders`."""

    def list_graders(self) -> tuple[GraderDescriptor, ...]: ...
