"""13-package-and-class-design §"应用层": ScoreRunService, the sole implementation of
ScoreRunUseCase. 04-use-cases UC-EI-002 step 5: "Grader registry 执行 deterministic
grader 与必要的 LLM Judge."
"""

from __future__ import annotations

import hashlib
import logging
import time

from opentelemetry import trace

from evaluationimprovement.application.commands import FinalizeRunScoringCommand, ScoreCaseCommand
from evaluationimprovement.application.exceptions import (
    CaseExecutionNotCompletedException,
    IncompleteRunException,
    RunNotFoundException,
    StaleResultException,
    TestCaseNotFoundException,
)
from evaluationimprovement.application.ports_out import (
    AuditRecordRepository,
    CaseExecutionResultRepository,
    ClockPort,
    EvaluationRunRepository,
    GraderRegistryPort,
    ScoreRepository,
    TelemetryArtifactPort,
    TestCaseRepository,
)
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.services.create_run import run_to_view, score_to_view
from evaluationimprovement.application.telemetry import EvaluationTelemetry
from evaluationimprovement.application.views import RunView, ScoreView
from evaluationimprovement.domain.enums import CaseExecutionStatus, RunStatus
from evaluationimprovement.domain.ids import ScoreId
from evaluationimprovement.domain.score import EvaluationScore
from evaluationimprovement.domain.values import EvidenceRef

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


class ScoreRunService:
    def __init__(
        self, run_repository: EvaluationRunRepository, test_case_repository: TestCaseRepository, score_repository: ScoreRepository,
        case_execution_result_repository: CaseExecutionResultRepository, grader_registry: GraderRegistryPort,
        telemetry_artifact_port: TelemetryArtifactPort, audit_record_repository: AuditRecordRepository, clock: ClockPort,
        telemetry: EvaluationTelemetry,
    ) -> None:
        self._run_repository = run_repository
        self._test_case_repository = test_case_repository
        self._score_repository = score_repository
        self._case_execution_result_repository = case_execution_result_repository
        self._grader_registry = grader_registry
        self._telemetry_artifact_port = telemetry_artifact_port
        self._clock = clock
        self._telemetry = telemetry
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def score_case(self, command: ScoreCaseCommand) -> tuple[ScoreView, ...]:
        """12-observability §"Traces": not itself one of the seven named "关键 span"
        (GraderRegistry.grade, wrapped inside the per-dimension loop below, is), but
        every span still shares the same trace — this one's own span_id is simply an
        ancestor of each grade() call's, which is what lets the structured log line
        below read a real (non-zero) traceId back out via the current span context.
        """
        with tracer.start_as_current_span("ScoreRunService.scoreCase"):
            return self._score_case_traced(command)

    def _score_case_traced(self, command: ScoreCaseCommand) -> tuple[ScoreView, ...]:
        run = self._run_repository.find_by_id(command.run_id)
        if run is None:
            raise RunNotFoundException(command.run_id)
        if run.status == RunStatus.RUNNING:
            run = run.enter_scoring()
            self._run_repository.save(run, expected_status=RunStatus.RUNNING)
        elif run.status != RunStatus.SCORING:
            raise ValueError(f"run {command.run_id} is {run.status} and cannot be scored")

        test_case = self._test_case_repository.find_by_id(command.test_case_id)
        if test_case is None:
            raise TestCaseNotFoundException(command.test_case_id)

        result = self._case_execution_result_repository.find(command.run_id, command.test_case_id)
        if result is None:
            raise ValueError(f"test case {command.test_case_id} has not been executed yet for run {command.run_id}")
        if result.status != CaseExecutionStatus.COMPLETED:
            # SPEC-EI-009: a FAILED/SKIPPED result carries no real execution data —
            # grading its default/empty fields would produce a meaningless score.
            raise CaseExecutionNotCompletedException(command.test_case_id, result.status)
        current_generation = self._run_repository.current_generation(command.run_id)
        if result.run_generation != command.run_generation or result.run_generation != current_generation:
            # 09-concurrency-and-idempotency §"Stale 结果": a mismatched generation must
            # never enter gate calculation — no score is written at all.
            raise StaleResultException(command.run_id, current_generation, result.run_generation)

        # 07-data-model §"Artifact 引用": the workflow trace a grader read is never
        # stored inline, only referenced. One reference per case (not per dimension)
        # since every dimension's grader reads the same CaseExecutionResult.
        evidence_ref = EvidenceRef(
            artifact_provider="agent-runtime", artifact_uri=result.workflow_trace_ref,
            artifact_hash=hashlib.sha256(result.workflow_trace_ref.encode()).hexdigest(), retention_until=None,
        )
        self._telemetry_artifact_port.store_reference(
            evidence_ref.artifact_provider, evidence_ref.artifact_uri, evidence_ref.artifact_hash, evidence_ref.retention_until,
        )

        # SPEC-EI-017 / 08-transaction-and-outbox §"事务原则": "写 score 时，score 可以按
        # case 分批提交" — every dimension this case grades lands in one save_many()
        # transaction, not N separate ones.
        stage_started_at = time.perf_counter()
        scores: list[EvaluationScore] = []
        for dimension, grader_type in self._grader_registry.dimensions_for_case(test_case):
            grader_result = self._grader_registry.grade(dimension, grader_type, test_case, result)
            score = EvaluationScore.create(
                score_id=ScoreId.new_id(), run_id=command.run_id, test_case_id=command.test_case_id, dimension=dimension,
                score=grader_result.score, threshold=grader_result.threshold, grader_type=grader_result.grader_type,
                grader_version=grader_result.grader_version, evidence_ref=evidence_ref, failure_code=grader_result.failure_code,
                details=grader_result.details,
            )
            scores.append(score)
            if score.failure_code is not None and score.failure_code.value == "GRADER_ERROR":
                self._telemetry.record_grader_error(grader_type.value, grader_result.grader_version)

        saved = self._score_repository.save_many(tuple(scores))
        self._telemetry.record_stage_latency("SCORE", time.perf_counter() - stage_started_at)
        # SPEC-EI-033 (observability-evaluation-signal-contract) / 12-observability
        # §"Metrics": evaluation_score/evaluation_cost_tokens_total, both real per-case
        # data (EvaluationScore.score / CaseExecutionResult.cost_tokens) nothing wired
        # into a metric before this spec. "model" has no dedicated identifier anywhere
        # in this domain's own data (only `target_version`, the agent build under
        # test) — used for both labels rather than inventing a model name that isn't
        # actually tracked.
        for s in saved:
            self._telemetry.record_score(s.dimension.value, run.version_binding.dataset_version, run.version_binding.target_version, s.score)
        self._telemetry.record_cost_tokens(run.version_binding.target_version, run.version_binding.target_version, result.cost_tokens)
        self._telemetry.record_stage_latency("EXECUTE", result.latency_ms / 1000.0)

        overall_passed = all(s.passed for s in saved) if saved else True
        self._telemetry.record_case_result("PASSED" if overall_passed else "FAILED", test_case.criticality.value)

        # 12-observability §"Logs": "结构化日志必须包含 runId/testCaseId/candidateId/
        # datasetVersion/targetVersion/graderVersion/traceId/correlationId/
        # failureCode." candidateId is not applicable to case scoring (an
        # ImprovementCandidate's own creation carries that field instead — see
        # CreateImprovementCandidateService's own docstring); graderVersion/
        # failureCode reflect the *last* dimension graded when a case has more than
        # one, the same "one representative log line per case" simplification most
        # structured-logging call sites in this codebase already make.
        span_context = trace.get_current_span().get_span_context()
        trace_id = trace.format_trace_id(span_context.trace_id) if span_context.is_valid else "0" * 32
        last_score = saved[-1] if saved else None
        logger.info(
            "action=score_case run_id=%s test_case_id=%s dataset_version=%s target_version=%s grader_version=%s "
            "trace_id=%s correlation_id=%s failure_code=%s",
            command.run_id, command.test_case_id, run.version_binding.dataset_version, run.version_binding.target_version,
            last_score.grader_version if last_score else None, trace_id, command.correlation_id,
            last_score.failure_code.value if last_score and last_score.failure_code else None,
        )
        return tuple(score_to_view(s) for s in saved)

    def finalize_scoring(self, command: FinalizeRunScoringCommand) -> RunView:
        """08-transaction-and-outbox §"Run 完成事务": every expected case must have a
        score, or be accounted for as FAILED/SKIPPED (SPEC-EI-009 / 10-failure-handling
        §"Partial Run"), before a run leaves SCORING. If every expected case is
        accounted for but at least one never actually got scored (a FAILED/SKIPPED
        case), the run finalizes as PARTIAL instead of COMPARING — release gate
        evaluation is never even attempted against an incomplete result set, matching
        10-failure-handling's own "Run 可进入 PARTIAL，但 release gate 不能 passed."
        SPEC-EI-009: also callable while the run is still RUNNING — score_case() is
        what normally drives RUNNING -> SCORING, but a run whose every case ends up
        FAILED/SKIPPED never calls score_case() at all, and must still be able to
        finalize (as PARTIAL, via the domain's own pre-existing RUNNING -> PARTIAL
        transition) rather than being stuck forever.
        """
        run = self._run_repository.find_by_id(command.run_id)
        if run is None:
            raise RunNotFoundException(command.run_id)
        if run.status not in (RunStatus.RUNNING, RunStatus.SCORING):
            raise ValueError(f"run {command.run_id} is {run.status}, expected SCORING")

        expected_cases = self._test_case_repository.find_by_dataset(run.dataset_id)
        expected_case_ids = {str(c.test_case_id) for c in expected_cases}
        scored_count = self._score_repository.count_distinct_scored_cases(command.run_id)

        execution_results = self._case_execution_result_repository.find_by_run(command.run_id)
        unscoreable_case_ids = {
            r.test_case_id
            for r in execution_results
            if r.test_case_id in expected_case_ids and r.status != CaseExecutionStatus.COMPLETED
        }
        accounted_for = scored_count + len(unscoreable_case_ids)
        if accounted_for < len(expected_cases):
            raise IncompleteRunException(command.run_id, len(expected_cases) - accounted_for)

        now = self._clock.now()
        if unscoreable_case_ids:
            finalized = run.mark_partial(now)
            outcome_detail = f'{{"outcome": "PARTIAL", "unaccountedForScoringCount": {len(unscoreable_case_ids)}}}'
        else:
            finalized = run.enter_comparing()
            outcome_detail = '{"outcome": "COMPARING"}'
        saved = self._run_repository.save(finalized, expected_status=run.status)
        self._audit_recorder.record(
            action="finalize_run_scoring", resource_type="EVALUATION_RUN", resource_id=str(saved.run_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id, detail=outcome_detail,
        )
        return run_to_view(saved)
