"""13-package-and-class-design §"应用层": CompareRegressionService, the sole
implementation of CompareRegressionUseCase and ReportQueryUseCase. 04-use-cases
UC-EI-002 step 6: "生成 score 和 regression report."
"""

from __future__ import annotations

from collections import defaultdict

from evaluationimprovement.application.commands import CompareRegressionCommand
from evaluationimprovement.application.exceptions import BaselineRunNotFoundException, ReportNotFoundException, RunNotFoundException
from evaluationimprovement.application.outbox_codec import build_outbox_record, to_correlation_id
from evaluationimprovement.application.ports_out import (
    CaseExecutionResultRepository,
    ClockPort,
    EvaluationRunRepository,
    OutboxRepository,
    RegressionReportRepository,
    ScoreRepository,
    TestCaseRepository,
)
from evaluationimprovement.application.views import RegressionReportView
from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, RunStatus
from evaluationimprovement.domain.events import EvaluationRegressionDetected
from evaluationimprovement.domain.ids import ReportId, RunId
from evaluationimprovement.domain.regression_report import RegressionReport
from evaluationimprovement.domain.values import GateResult, MetricDiff

# 12-observability §"Metrics": `evaluation_score{dimension,...}` — dimensions where a
# *lower* value is the improvement (cost, latency); every other dimension is
# higher-is-better. Used only to decide regression direction for metric_diffs, never
# to influence the zero-tolerance gates below.
_LOWER_IS_BETTER = frozenset({EvaluationDimension.TOKEN_COST, EvaluationDimension.LATENCY})


class CompareRegressionService:
    def __init__(
        self, run_repository: EvaluationRunRepository, test_case_repository: TestCaseRepository, score_repository: ScoreRepository,
        case_execution_result_repository: CaseExecutionResultRepository, regression_report_repository: RegressionReportRepository,
        outbox_repository: OutboxRepository, clock: ClockPort,
    ) -> None:
        self._run_repository = run_repository
        self._test_case_repository = test_case_repository
        self._score_repository = score_repository
        self._case_execution_result_repository = case_execution_result_repository
        self._regression_report_repository = regression_report_repository
        self._outbox_repository = outbox_repository
        self._clock = clock

    def compare(self, command: CompareRegressionCommand) -> RegressionReportView:
        run = self._run_repository.find_by_id(command.run_id)
        if run is None:
            raise RunNotFoundException(command.run_id)
        if run.status != RunStatus.COMPARING:
            raise ValueError(f"run {command.run_id} is {run.status}, expected COMPARING")

        baseline_run = None
        if command.baseline_run_id is not None:
            # 09-concurrency-and-idempotency §"并发规则": "Baseline comparison 必须锁定
            # baseline run id，不能比较移动中的 latest" — the baseline must already be a
            # terminal, PASSED run; a still-in-flight run can never be a baseline.
            baseline_run = self._run_repository.find_by_id(command.baseline_run_id)
            if baseline_run is None or baseline_run.status != RunStatus.PASSED:
                raise BaselineRunNotFoundException(command.baseline_run_id)

        candidate_scores = self._score_repository.find_active_by_run(command.run_id)
        baseline_scores = (
            self._score_repository.find_active_by_run(command.baseline_run_id) if baseline_run is not None else []
        )
        metric_diffs = _build_metric_diffs(candidate_scores, baseline_scores)

        # 02-business-invariants INV-EI-003: only a DETERMINISTIC-graded failure can
        # ever mark a critical case as failed here — an LLM_JUDGE score (always
        # quality-only, e.g. UNSCORED in this spec's own placeholder judge) must never
        # fail the release gate on its own.
        test_cases_by_id = {c.test_case_id: c for c in self._test_case_repository.find_by_dataset(run.dataset_id)}
        critical_failures = tuple(
            sorted({
                str(s.test_case_id) for s in candidate_scores
                if not s.passed and s.grader_type == GraderType.DETERMINISTIC
                and test_cases_by_id.get(s.test_case_id) is not None and test_cases_by_id[s.test_case_id].is_critical
            })
        )

        execution_results = self._case_execution_result_repository.find_by_run(command.run_id)
        policy_violation_total = sum(r.policy_violation_count for r in execution_results)
        forbidden_tool_total = sum(r.forbidden_tool_call_count for r in execution_results)
        unauthorized_memory_total = sum(r.unauthorized_memory_access_count for r in execution_results)

        # 02-business-invariants INV-EI-004 / INV-EI-003: these three gates are
        # computed from CaseExecutionResult counters supplied by the runtime itself —
        # never from any grader's opinion, deterministic or otherwise — so an LLM
        # Judge can never influence them, by construction.
        gate_results = (
            GateResult("policy_violation_zero", passed=policy_violation_total == 0, reason=f"count={policy_violation_total}"),
            GateResult("forbidden_tool_zero", passed=forbidden_tool_total == 0, reason=f"count={forbidden_tool_total}"),
            GateResult(
                "unauthorized_memory_access_zero", passed=unauthorized_memory_total == 0,
                reason=f"count={unauthorized_memory_total}",
            ),
        )

        report = RegressionReport.create(
            report_id=ReportId.new_id(), run_id=command.run_id, baseline_run_id=command.baseline_run_id,
            metric_diffs=metric_diffs, gate_results=gate_results, critical_failures=critical_failures,
            recommendation=_recommendation(critical_failures, gate_results), now=self._clock.now(),
        )
        saved = self._regression_report_repository.save(report)

        if baseline_run is not None:
            regressed = _regressed_dimensions(metric_diffs)
            if regressed:
                now = self._clock.now()
                self._outbox_repository.append(build_outbox_record(
                    EvaluationRegressionDetected(
                        run_id=saved.run_id, report_id=saved.report_id, regressed_dimensions=regressed, occurred_at=now,
                    ),
                    "evaluation.regression.detected.v1", aggregate_id=str(saved.report_id), occurred_at=now,
                    correlation_id=to_correlation_id(run.version_binding.correlation_id),
                ))

        return _to_view(saved)

    def find_report(self, report_id: ReportId) -> RegressionReportView:
        report = self._regression_report_repository.find_by_id(report_id)
        if report is None:
            raise ReportNotFoundException(report_id)
        return _to_view(report)

    def find_report_for_run(self, run_id: RunId) -> RegressionReportView:
        report = self._regression_report_repository.find_by_run(run_id)
        if report is None:
            raise ReportNotFoundException(run_id)
        return _to_view(report)


def _build_metric_diffs(candidate_scores, baseline_scores) -> tuple[MetricDiff, ...]:  # noqa: ANN001
    def _averages(scores) -> dict[EvaluationDimension, float]:  # noqa: ANN001
        # 02-business-invariants INV-EI-003: only DETERMINISTIC-graded scores ever
        # feed a gate/regression-diff decision; LLM_JUDGE output stays quality-only
        # informational data this method never reads.
        by_dimension: dict[EvaluationDimension, list[float]] = defaultdict(list)
        for s in scores:
            if s.grader_type == GraderType.DETERMINISTIC:
                by_dimension[s.dimension].append(s.score)
        return {dim: sum(values) / len(values) for dim, values in by_dimension.items()}

    candidate_avg = _averages(candidate_scores)
    baseline_avg = _averages(baseline_scores)
    dimensions = sorted(set(candidate_avg) | set(baseline_avg), key=lambda d: d.value)
    return tuple(
        MetricDiff(dimension=d.value, baseline_value=baseline_avg.get(d, 0.0), candidate_value=candidate_avg.get(d, 0.0))
        for d in dimensions
    )


def _regressed_dimensions(metric_diffs: tuple[MetricDiff, ...]) -> tuple[str, ...]:
    """A dimension regressed when its candidate value moved the wrong direction
    relative to baseline: worse (lower) for a higher-is-better dimension, or worse
    (higher) for a lower-is-better one (`_LOWER_IS_BETTER`: TOKEN_COST/LATENCY). A
    diff with no baseline recorded (baseline_value == 0.0 and dimension never scored
    in the baseline run) is not treated as a regression — there is nothing to regress
    from.
    """
    regressed = []
    for diff in metric_diffs:
        try:
            dimension = EvaluationDimension(diff.dimension)
        except ValueError:
            continue
        if diff.baseline_value == 0.0 and diff.candidate_value == 0.0:
            continue
        worse = diff.delta > 0 if dimension in _LOWER_IS_BETTER else diff.delta < 0
        if worse:
            regressed.append(diff.dimension)
    return tuple(regressed)


def _recommendation(critical_failures: tuple[str, ...], gate_results: tuple[GateResult, ...]) -> str:
    failed_gates = [g.gate_name for g in gate_results if not g.passed]
    if critical_failures or failed_gates:
        return f"block_promotion: critical_failures={len(critical_failures)} failed_gates={failed_gates}"
    return "eligible_for_release_gate_evaluation"


def _to_view(report: RegressionReport) -> RegressionReportView:
    return RegressionReportView(
        report_id=report.report_id, run_id=report.run_id, baseline_run_id=report.baseline_run_id,
        overall_decision=report.overall_decision, critical_failures=report.critical_failures,
        recommendation=report.recommendation, created_at=report.created_at,
    )
