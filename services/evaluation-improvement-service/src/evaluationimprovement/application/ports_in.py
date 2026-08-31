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
    CollectOnlineSampleCommand,
    CompareRegressionCommand,
    CompleteCanaryRollbackCommand,
    ConsumeApprovalDeniedCommand,
    ConsumeApprovalGrantedCommand,
    ConsumeMemoryRetrievalCompletedCommand,
    ConsumeTicketReopenedCommand,
    ConsumeTicketResolvedCommand,
    ConsumeToolCompletedCommand,
    ConsumeWorkflowCompletedCommand,
    ConsumeWorkflowFailedCommand,
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
    RollbackPromotedCandidateCommand,
    ScoreCaseCommand,
    SkipCaseCommand,
    StartCanaryCommand,
    SubmitDatasetForReviewCommand,
)
from evaluationimprovement.application.records import AuditRecordEntry, GatePolicyConfig, PoisonEventRecord
from evaluationimprovement.application.views import (
    CanaryPromotionDecisionView,
    CaseRunnerReport,
    DatasetView,
    DispatchReport,
    FailureClusterView,
    GraderDescriptor,
    ImprovementCandidateView,
    OnlineEvaluationSampleView,
    OnlineSampleScoringReport,
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

    def find_scores(self, run_id: RunId, actor: str, actor_role: str) -> tuple[ScoreView, ...]:
        """SPEC-EI-034 (evaluation-security-redaction-observability): `actor`/
        `actor_role` gate whether sensitive per-score evidence is included — see
        CreateRunService.find_scores()'s own docstring.
        """
        ...

    def list_runs(self, dataset_id: DatasetId, status: str | None, limit: int) -> tuple[RunView, ...]:
        """SPEC-EI-010 / 05-api-contracts: "状态可见性" — `GET
        /evaluation/runs?dataset_id=&status=`, newest first.
        """
        ...

    def find_stuck_runs(self, sla_seconds: int) -> tuple[RunView, ...]:
        """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 12-observability
        §"Alerts": "run stuck in RUNNING 或 SCORING beyond SLA" — see
        EvaluationRunRepository.find_stuck()'s own docstring for how "stuck" is
        measured. Oldest first.
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

    def rollback_promoted(self, command: RollbackPromotedCandidateCommand) -> ImprovementCandidateView:
        """SPEC-EI-036: a promoted candidate's own rollback path — see
        RollbackPromotedCandidateCommand's own docstring for why this is distinct
        from request_rollback()/complete_rollback()'s own in-progress-canary shape.
        """
        ...


class OutboxDispatchPort(Protocol):
    """08-transaction-and-outbox §"Outbox 发布". Not a domain use case, an operational
    surface — mirrors memory-knowledge-service's own OutboxDispatchPort.
    """

    def dispatch_due_events(self, batch_size: int) -> DispatchReport: ...


class CaseRunnerPort(Protocol):
    """SPEC-EI-011: an operational surface, not a domain use case — mirrors
    OutboxDispatchPort exactly. infrastructure.runtime.case_runner_worker.
    CaseRunnerWorker is this port's only real caller (a scheduled/looped worker
    process); tests reach it directly through container.case_runner_port the same way
    other tests reach container.outbox_dispatch_port.
    """

    def run_once(self, worker_id: str, batch_size: int) -> CaseRunnerReport: ...

    def reclaim_expired_leases(self, batch_size: int) -> int: ...


class AuditQueryUseCase(Protocol):
    """05-api-contracts §"管理 API": `GET /evaluation/audit`."""

    def list_audit_events(self, limit: int) -> list[AuditRecordEntry]: ...


class AdminRecoveryUseCase(Protocol):
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 05-api-contracts
    §"管理 API": `POST /evaluation/outbox/dispatch` — the manual replay trigger
    10-failure-handling step "支持 admin replay" names, audited (unlike the plain
    `OutboxDispatchPort.dispatch_due_events()` this wraps).
    """

    def dispatch_outbox_events(self, batch_size: int, actor: str, correlation_id: str) -> DispatchReport: ...


class PoisonEventQueryUseCase(Protocol):
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 05-api-contracts
    §"管理 API": `GET /evaluation/poison-events` — 10-failure-handling step 4's own
    "支持 admin replay" surface: an operator sees what needs fixing, fixes the
    upstream issue, then re-POSTs the same event to the same ingestion endpoint
    (never marked processed, so the dedup gate lets it through again).
    """

    def list_poison_events(self, limit: int) -> list[PoisonEventRecord]: ...


class GatePolicyUseCase(Protocol):
    """05-api-contracts §"管理 API": `GET/PUT /evaluation/gates/{gatePolicy}`."""

    def find_gate_policy(self, gate_policy: str) -> GatePolicyConfig: ...

    def upsert_gate_policy(self, config: GatePolicyConfig, actor: str) -> GatePolicyConfig: ...


class GraderCatalogUseCase(Protocol):
    """05-api-contracts §"管理 API": `GET /evaluation/graders`."""

    def list_graders(self) -> tuple[GraderDescriptor, ...]: ...


class CrossDomainEventConsumerPort(Protocol):
    """SPEC-EI-030 (ticket-runtime-evaluation-contract) / SPEC-EI-031 (memory-tool-
    evidence-contract): `POST /internal/evaluation/v1/events/*` — a manual/ops
    trigger until a real RabbitMQ async consumer exists, mirroring
    memory-knowledge-service's own interfaces/event/router.py precedent exactly.
    """

    def consume_ticket_resolved(self, command: ConsumeTicketResolvedCommand) -> bool: ...

    def consume_ticket_reopened(self, command: ConsumeTicketReopenedCommand) -> bool: ...

    def consume_workflow_completed(self, command: ConsumeWorkflowCompletedCommand) -> bool: ...

    def consume_workflow_failed(self, command: ConsumeWorkflowFailedCommand) -> bool: ...

    def consume_tool_completed(self, command: ConsumeToolCompletedCommand) -> bool: ...

    def consume_memory_retrieval_completed(self, command: ConsumeMemoryRetrievalCompletedCommand) -> bool: ...


class ApprovalDecisionEventConsumerPort(Protocol):
    """SPEC-EI-032 (policy-approval-release-approval-contract): `POST
    /internal/evaluation/v1/events/approval-granted`/`.../approval-denied`.
    """

    def consume_granted(self, command: ConsumeApprovalGrantedCommand) -> bool: ...

    def consume_denied(self, command: ConsumeApprovalDeniedCommand) -> bool: ...


class FailureClusterQueryUseCase(Protocol):
    """SPEC-EI-023 (failure-clustering-root-cause-taxonomy): `GET
    /evaluation/runs/{runId}/failure-clusters`.
    """

    def list_clusters(self, run_id: RunId) -> tuple[FailureClusterView, ...]: ...


class OnlineSampleUseCase(Protocol):
    """SPEC-EI-028 (online-sample-evaluation): `POST /evaluation/online-samples`."""

    def collect(self, command: CollectOnlineSampleCommand) -> OnlineEvaluationSampleView: ...

    def find_samples_for_candidate(self, candidate_id: CandidateId) -> tuple[OnlineEvaluationSampleView, ...]: ...


class CanaryPromotionUseCase(Protocol):
    """SPEC-EI-029 (promotion-criteria-rollback-request): `GET
    /evaluation/improvement-candidates/{candidateId}/promotion-criteria`.
    """

    def evaluate(self, candidate_id: CandidateId) -> CanaryPromotionDecisionView: ...


class OnlineSampleScoringPort(Protocol):
    """SPEC-EI-028: an operational surface, not a domain use case — mirrors
    OutboxDispatchPort/CaseRunnerPort exactly. No standing worker process consumes it
    yet (04-use-cases UC-EI-006's own step 4 is delayed, non-blocking scoring, not a
    synchronous call any REST request waits on); tests reach it directly through
    container.online_sample_scoring_port the same way other tests reach
    container.outbox_dispatch_port.
    """

    def score_pending(self, batch_size: int) -> OnlineSampleScoringReport: ...
