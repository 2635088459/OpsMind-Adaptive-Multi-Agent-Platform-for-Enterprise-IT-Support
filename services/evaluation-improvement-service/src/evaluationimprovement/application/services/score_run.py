"""13-package-and-class-design §"应用层": ScoreRunService, the sole implementation of
ScoreRunUseCase. 04-use-cases UC-EI-002 step 5: "Grader registry 执行 deterministic
grader 与必要的 LLM Judge."
"""

from __future__ import annotations

import hashlib

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

        scores: list[EvaluationScore] = []
        for dimension, grader_type in self._grader_registry.dimensions_for_case(test_case):
            grader_result = self._grader_registry.grade(dimension, grader_type, test_case, result)
            score = EvaluationScore.create(
                score_id=ScoreId.new_id(), run_id=command.run_id, test_case_id=command.test_case_id, dimension=dimension,
                score=grader_result.score, threshold=grader_result.threshold, grader_type=grader_result.grader_type,
                grader_version=grader_result.grader_version, evidence_ref=evidence_ref, failure_code=grader_result.failure_code,
                details=grader_result.details,
            )
            saved = self._score_repository.save(score)
            scores.append(saved)
            if saved.failure_code is not None and saved.failure_code.value == "GRADER_ERROR":
                self._telemetry.record_grader_error(grader_type.value, grader_result.grader_version)

        overall_passed = all(s.passed for s in scores) if scores else True
        self._telemetry.record_case_result("PASSED" if overall_passed else "FAILED", test_case.criticality.value)
        return tuple(score_to_view(s) for s in scores)

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
