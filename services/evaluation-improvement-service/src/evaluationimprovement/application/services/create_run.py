"""13-package-and-class-design §"应用层": CreateRunService, the sole implementation
of CreateRunUseCase and RunQueryUseCase. 04-use-cases UC-EI-002 steps 1-2: "Admin 或
CI 提交 target version、baseline version 和 dataset version" / "07 创建 EvaluationRun."
"""

from __future__ import annotations

import dataclasses
from datetime import timedelta

from opentelemetry import trace

from evaluationimprovement.application.commands import CancelRunCommand, CreateRunCommand
from evaluationimprovement.application.exceptions import DatasetNotFoundException, RunKeyConflictException, RunNotFoundException
from evaluationimprovement.application.outbox_codec import build_outbox_record, to_correlation_id
from evaluationimprovement.application.ports_out import (
    AuditRecordRepository,
    AuthorizationPort,
    CaseExecutionQueueRepository,
    ClockPort,
    DatasetRepository,
    EvaluationRunRepository,
    LangSmithLinkRepository,
    LangSmithPort,
    OutboxRepository,
    ScoreRepository,
    TestCaseRepository,
)
from evaluationimprovement.application.records import LangSmithLinkRecord
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.views import RunView, ScoreView
from evaluationimprovement.domain.enums import DatasetStatus, RunStatus
from evaluationimprovement.domain.events import EvaluationRunRequested
from evaluationimprovement.domain.evaluation_run import EvaluationRun
from evaluationimprovement.domain.ids import DatasetId, RunId
from evaluationimprovement.domain.score import EvaluationScore
from evaluationimprovement.domain.values import VersionBinding

tracer = trace.get_tracer(__name__)


class CreateRunService:
    def __init__(
        self, dataset_repository: DatasetRepository, test_case_repository: TestCaseRepository,
        run_repository: EvaluationRunRepository, score_repository: ScoreRepository,
        case_execution_queue_repository: CaseExecutionQueueRepository, langsmith_port: LangSmithPort,
        langsmith_link_repository: LangSmithLinkRepository, outbox_repository: OutboxRepository,
        audit_record_repository: AuditRecordRepository, clock: ClockPort, authorization_port: AuthorizationPort,
    ) -> None:
        self._dataset_repository = dataset_repository
        self._test_case_repository = test_case_repository
        self._run_repository = run_repository
        self._score_repository = score_repository
        self._case_execution_queue_repository = case_execution_queue_repository
        self._langsmith_port = langsmith_port
        self._langsmith_link_repository = langsmith_link_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._authorization_port = authorization_port
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def create_run(self, command: CreateRunCommand) -> RunView:
        """09-concurrency-and-idempotency §"并发规则": "同一个 runKey 重复提交必须返回同一
        run." A resubmission against a different dataset raises RunKeyConflictException
        instead of silently reusing the stale run.
        """
        with tracer.start_as_current_span("EvaluationRunService.createRun"):
            return self._create_run_traced(command)

    def _create_run_traced(self, command: CreateRunCommand) -> RunView:
        existing = self._run_repository.find_by_run_key(command.run_key)
        if existing is not None:
            if existing.dataset_id != command.dataset_id or existing.version_binding.target_version != command.target_version:
                raise RunKeyConflictException(command.run_key)
            return run_to_view(existing)

        dataset = self._dataset_repository.find_by_id(command.dataset_id)
        if dataset is None:
            raise DatasetNotFoundException(command.dataset_id)
        if dataset.status != DatasetStatus.PUBLISHED:
            raise ValueError(f"dataset {command.dataset_id} must be PUBLISHED to run an evaluation against it")

        now = self._clock.now()
        version_binding = VersionBinding(
            dataset_version=dataset.version, target_version=command.target_version,
            grader_bundle_version=command.grader_bundle_version, policy_version=command.policy_version,
            correlation_id=command.correlation_id, baseline_version=command.baseline_version,
        )
        run = EvaluationRun.create(
            run_id=RunId.new_id(), run_key=command.run_key, dataset_id=command.dataset_id,
            version_binding=version_binding, triggered_by=command.triggered_by, now=now,
        )
        saved = self._run_repository.save(run, expected_status=None)

        # 04-use-cases UC-EI-002 step 4: "07 收集 LangSmith experiment." SPEC-EI-013:
        # the outcome is persisted (never just logged) so EvaluateReleaseGateService
        # can enforce 10-failure-handling's own "对离线 release gate：fail closed" later,
        # without re-calling LangSmith at gate time.
        experiment_ref = self._langsmith_port.link_experiment(saved.run_id, dataset.name, dataset.version)
        self._langsmith_link_repository.save(LangSmithLinkRecord(
            run_id=str(saved.run_id), enabled=self._langsmith_port.is_enabled(), experiment_ref=experiment_ref,
        ))

        # SPEC-EI-011: every test case in the dataset becomes one claimable queue
        # entry — the seam interfaces.rest.router's own "Pipeline steps" comment named
        # ("these admin-triggered endpoints are the seam a future runner/worker spec
        # will call instead of a human") is CaseRunnerService, not this enqueue call
        # itself; enqueueing only makes the work visible, it never executes anything.
        test_cases = self._test_case_repository.find_by_dataset(command.dataset_id)
        if test_cases:
            self._case_execution_queue_repository.enqueue_many(
                saved.run_id, tuple(c.test_case_id for c in test_cases), self._run_repository.current_generation(saved.run_id), now,
            )

        # 08-transaction-and-outbox §"事务原则": "创建 run 时，evaluation_runs 与
        # evaluation.run.requested.v1 outbox 必须同事务提交." (In-memory adapters commit
        # synchronously in this call, mirroring what a real Postgres transaction will
        # do once SPEC-EI-002/SPEC-EI-003 land.)
        correlation_id = to_correlation_id(command.correlation_id)
        self._outbox_repository.append(build_outbox_record(
            EvaluationRunRequested(run_id=saved.run_id, run_key=saved.run_key, occurred_at=now),
            "evaluation.run.requested.v1", aggregate_id=str(saved.run_id), occurred_at=now, correlation_id=correlation_id,
        ))
        self._audit_recorder.record(
            action="create_run", resource_type="EVALUATION_RUN", resource_id=str(saved.run_id), actor=command.actor,
            outcome="SUCCESS", correlation_id=command.correlation_id,
            detail=f'{{"langsmithExperimentRef": {experiment_ref!r}}}',
        )
        return run_to_view(saved)

    def cancel_run(self, command: CancelRunCommand) -> RunView:
        """09-concurrency-and-idempotency §"并发规则": a resubmitted cancel against an
        already-CANCELLED run must return that same run, not raise — mirroring
        create_run()'s own runKey idempotency. Any other terminal status (PASSED/
        FAILED/PARTIAL) still refuses via the domain's own state machine: those are
        genuinely final outcomes a cancel can no longer undo or short-circuit.
        """
        run = self._run_repository.find_by_id(command.run_id)
        if run is None:
            raise RunNotFoundException(command.run_id)
        if run.status == RunStatus.CANCELLED:
            return run_to_view(run)
        original_status = run.status
        cancelled = run.cancel(self._clock.now())
        saved = self._run_repository.save(cancelled, expected_status=original_status)
        self._audit_recorder.record(
            action="cancel_run", resource_type="EVALUATION_RUN", resource_id=str(saved.run_id), actor=command.actor,
            outcome="SUCCESS", correlation_id=command.correlation_id, detail=f'{{"reason": {command.reason!r}}}',
        )
        return run_to_view(saved)

    def find_run(self, run_id: RunId) -> RunView:
        run = self._run_repository.find_by_id(run_id)
        if run is None:
            raise RunNotFoundException(run_id)
        return run_to_view(run)

    def find_scores(self, run_id: RunId, actor: str, actor_role: str) -> tuple[ScoreView, ...]:
        """SPEC-EI-034 (evaluation-security-redaction-observability) / 11-security
        §"数据保护": "Report 默认展示聚合分数；case-level evidence 需要更高权限." A caller
        without `can_view_sensitive_evidence()` still sees every score (dimension/
        score/passed/failure_code) — only `evidence_ref`/`details` (the workflow
        trace reference and any raw grader detail) are stripped. Access is audited
        only when evidence is actually granted, never on the aggregate-score-only
        path (auditing every ordinary score read would drown the real signal this
        rule exists to catch).
        """
        if self._run_repository.find_by_id(run_id) is None:
            raise RunNotFoundException(run_id)
        scores = tuple(score_to_view(s) for s in self._score_repository.find_active_by_run(run_id))
        if self._authorization_port.can_view_sensitive_evidence(actor_role):
            if scores:
                self._audit_recorder.record(
                    action="view_sensitive_evidence", resource_type="EVALUATION_RUN", resource_id=str(run_id),
                    actor=actor, outcome="SUCCESS",
                )
            return scores
        return tuple(dataclasses.replace(s, evidence_ref=None, details={}) for s in scores)

    def list_runs(self, dataset_id: DatasetId, status: str | None, limit: int) -> tuple[RunView, ...]:
        """SPEC-EI-010 / 05-api-contracts: "状态可见性" — every run against a dataset,
        newest first, optionally narrowed to one status.
        """
        if self._dataset_repository.find_by_id(dataset_id) is None:
            raise DatasetNotFoundException(dataset_id)
        status_filter = RunStatus[status] if status is not None else None
        return tuple(run_to_view(r) for r in self._run_repository.find_by_dataset(dataset_id, status_filter, limit))

    def find_stuck_runs(self, sla_seconds: int) -> tuple[RunView, ...]:
        older_than = self._clock.now() - timedelta(seconds=sla_seconds)
        stuck = self._run_repository.find_stuck(frozenset({RunStatus.RUNNING, RunStatus.SCORING}), older_than)
        return tuple(run_to_view(r) for r in stuck)


def run_to_view(run: EvaluationRun) -> RunView:
    return RunView(
        run_id=run.run_id, run_key=run.run_key, dataset_id=run.dataset_id,
        dataset_version=run.version_binding.dataset_version, target_version=run.version_binding.target_version,
        baseline_version=run.version_binding.baseline_version, status=run.status, triggered_by=run.triggered_by,
        started_at=run.started_at, completed_at=run.completed_at,
    )


def score_to_view(score: EvaluationScore) -> ScoreView:
    return ScoreView(
        score_id=score.score_id, run_id=score.run_id, test_case_id=score.test_case_id, dimension=score.dimension,
        score=score.score, passed=score.passed, grader_type=score.grader_type, grader_version=score.grader_version,
        failure_code=score.failure_code, evidence_ref=score.evidence_ref, details=score.details,
    )
