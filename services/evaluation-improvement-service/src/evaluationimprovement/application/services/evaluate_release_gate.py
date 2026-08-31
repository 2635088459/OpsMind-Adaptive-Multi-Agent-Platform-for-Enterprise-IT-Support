"""13-package-and-class-design §"应用层": EvaluateReleaseGateService, the sole
implementation of EvaluateReleaseGateUseCase and GatePolicyUseCase. 04-use-cases
UC-EI-003: "读取 score、baseline score 和 gate policy" / "输出 PASSED 或 FAILED."
"""

from __future__ import annotations

import time

from opentelemetry import trace

from evaluationimprovement.application.commands import EvaluateReleaseGateCommand
from evaluationimprovement.application.exceptions import GatePolicyNotFoundException, ReportNotFoundException, RunNotFoundException
from evaluationimprovement.application.outbox_codec import build_outbox_record, to_correlation_id
from evaluationimprovement.application.ports_out import (
    AuditRecordRepository,
    ClockPort,
    EvaluationRunRepository,
    GatePolicyRepository,
    LangSmithLinkRepository,
    OutboxRepository,
    RegressionReportRepository,
    ScoreRepository,
)
from evaluationimprovement.application.records import GatePolicyConfig
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.telemetry import EvaluationTelemetry
from evaluationimprovement.application.views import RegressionReportView
from evaluationimprovement.domain.enums import GateDecision, GraderType, RunStatus
from evaluationimprovement.domain.events import EvaluationGateFailed, EvaluationGatePassed, EvaluationRunCompleted
from evaluationimprovement.domain.regression_report import RegressionReport

tracer = trace.get_tracer(__name__)


class EvaluateReleaseGateService:
    def __init__(
        self, run_repository: EvaluationRunRepository, score_repository: ScoreRepository,
        regression_report_repository: RegressionReportRepository, gate_policy_repository: GatePolicyRepository,
        langsmith_link_repository: LangSmithLinkRepository, outbox_repository: OutboxRepository,
        audit_record_repository: AuditRecordRepository, clock: ClockPort, telemetry: EvaluationTelemetry,
    ) -> None:
        self._run_repository = run_repository
        self._score_repository = score_repository
        self._regression_report_repository = regression_report_repository
        self._gate_policy_repository = gate_policy_repository
        self._langsmith_link_repository = langsmith_link_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._telemetry = telemetry
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def evaluate(self, command: EvaluateReleaseGateCommand) -> RegressionReportView:
        stage_started_at = time.perf_counter()
        with tracer.start_as_current_span("ReleaseGateEvaluator.evaluate"):
            try:
                return self._evaluate_traced(command)
            finally:
                self._telemetry.record_stage_latency("GATE", time.perf_counter() - stage_started_at)

    def _evaluate_traced(self, command: EvaluateReleaseGateCommand) -> RegressionReportView:
        run = self._run_repository.find_by_id(command.run_id)
        if run is None:
            raise RunNotFoundException(command.run_id)
        if run.status != RunStatus.COMPARING:
            raise ValueError(f"run {command.run_id} is {run.status}, expected COMPARING")

        report = self._regression_report_repository.find_by_run(command.run_id)
        if report is None:
            raise ReportNotFoundException(command.run_id)

        gate_config = self._gate_policy_repository.find_by_name(command.gate_policy)
        if gate_config is None:
            raise GatePolicyNotFoundException(command.gate_policy)

        threshold_failures = self._threshold_failures(command.run_id, gate_config)
        # 02-business-invariants INV-EI-002/INV-EI-008: the zero-tolerance/critical-
        # case decision RegressionReport.create() already derived is never overridden
        # here, only ever tightened further by dimension-threshold checks.
        base_passed = report.overall_decision == GateDecision.PASSED or not gate_config.critical_case_required
        # SPEC-EI-013 / 10-failure-handling §"LangSmith 故障": "对离线 release gate：fail
        # closed." A missing/never-saved link record is treated the same as an
        # explicit failed link — domain-rules "forbidden": "在缺失 source linkage、版本、
        # hash 或 correlation id 时产出 passed gate" leaves no room for "unknown" to mean
        # "assume fine" (CreateRunService always saves one, so this only matters for a
        # pre-this-feature or corrupted run). A disabled (never-attempted) LangSmith
        # integration is not a failure, so it never affects this — see
        # LangSmithLinkRecord's own docstring.
        link = self._langsmith_link_repository.find(command.run_id)
        langsmith_ok = link is not None and (not link.enabled or link.experiment_ref is not None)
        final_passed = base_passed and not threshold_failures and langsmith_ok

        now = self._clock.now()
        run = run.pass_(now) if final_passed else run.fail(now)
        saved_run = self._run_repository.save(run, expected_status=RunStatus.COMPARING)

        correlation_id = to_correlation_id(run.version_binding.correlation_id)
        if final_passed:
            self._outbox_repository.append(build_outbox_record(
                EvaluationGatePassed(run_id=saved_run.run_id, report_id=report.report_id, gate_policy=command.gate_policy, occurred_at=now),
                "evaluation.gate.passed.v1", aggregate_id=str(report.report_id), occurred_at=now, correlation_id=correlation_id,
            ))
            self._telemetry.record_gate_passed(command.gate_policy)
        else:
            self._outbox_repository.append(build_outbox_record(
                EvaluationGateFailed(
                    run_id=saved_run.run_id, report_id=report.report_id, gate_policy=command.gate_policy,
                    critical_failure_count=len(report.critical_failures), occurred_at=now,
                ),
                "evaluation.gate.failed.v1", aggregate_id=str(report.report_id), occurred_at=now, correlation_id=correlation_id,
            ))
            if not langsmith_ok:
                reason = "langsmith_unavailable"
            elif not base_passed:
                reason = "critical_case_or_zero_tolerance_gate"
            else:
                reason = f"threshold:{threshold_failures}"
            self._telemetry.record_gate_failed(command.gate_policy, reason)

        self._outbox_repository.append(build_outbox_record(
            EvaluationRunCompleted(run_id=saved_run.run_id, status=saved_run.status.value, occurred_at=now),
            "evaluation.run.completed.v1", aggregate_id=str(saved_run.run_id), occurred_at=now, correlation_id=correlation_id,
        ))
        self._audit_recorder.record(
            action="evaluate_release_gate", resource_type="EVALUATION_RUN", resource_id=str(saved_run.run_id),
            actor=command.actor, outcome="PASSED" if final_passed else "FAILED", correlation_id=command.correlation_id,
        )
        self._telemetry.record_run_status(saved_run.status.value, str(run.dataset_id), run.version_binding.target_version)
        return _report_to_view(report)

    def _threshold_failures(self, run_id, gate_config: GatePolicyConfig) -> list[str]:  # noqa: ANN001
        """02-business-invariants INV-EI-003: only DETERMINISTIC-graded scores are ever
        compared against a gate policy's dimension thresholds — an LLM_JUDGE score
        (always UNSCORED in this spec's own placeholder judge, see infrastructure.
        graders.llm_judge's own docstring) can never fail a gate.
        """
        scores = [s for s in self._score_repository.find_active_by_run(run_id) if s.grader_type == GraderType.DETERMINISTIC]
        totals: dict[str, list[float]] = {}
        for s in scores:
            totals.setdefault(s.dimension.value, []).append(s.score)
        failures = []
        for dimension, threshold in gate_config.dimension_thresholds.items():
            values = totals.get(dimension)
            if not values:
                continue
            average = sum(values) / len(values)
            if average < threshold:
                failures.append(f"{dimension}:{average:.3f}<{threshold}")
        return failures

    def find_gate_policy(self, gate_policy: str) -> GatePolicyConfig:
        config = self._gate_policy_repository.find_by_name(gate_policy)
        if config is None:
            raise GatePolicyNotFoundException(gate_policy)
        return config

    def upsert_gate_policy(self, config: GatePolicyConfig, actor: str) -> GatePolicyConfig:
        """11-security §"审计": gate policy change must be audited.
        02-business-invariants INV-EI-004: the three max_* zero-tolerance counters may
        only ever be tightened to (or kept at) zero, never loosened past it.
        """
        if config.max_policy_violations > 0 or config.max_forbidden_tool_calls > 0 or config.max_unauthorized_memory_access > 0:
            raise ValueError("gate policy cannot loosen the zero-tolerance safety counters above zero")
        saved = self._gate_policy_repository.save(config)
        self._audit_recorder.record(
            action="upsert_gate_policy", resource_type="GATE_POLICY", resource_id=config.gate_policy, actor=actor,
            outcome="SUCCESS",
        )
        return saved


def _report_to_view(report: RegressionReport) -> RegressionReportView:
    return RegressionReportView(
        report_id=report.report_id, run_id=report.run_id, baseline_run_id=report.baseline_run_id,
        overall_decision=report.overall_decision, critical_failures=report.critical_failures,
        recommendation=report.recommendation, created_at=report.created_at,
    )
