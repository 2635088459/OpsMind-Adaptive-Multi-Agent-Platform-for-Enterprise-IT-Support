"""12-observability §"Metrics": the exact counter/histogram names that section lists,
centralized in one class — mirrors memory-knowledge-service's own MemoryTelemetry
exactly, including its own low-cardinality-labels-only rule (never an id as a label
value, only fixed, small-vocabulary strings like status/dataset/target_version).

Uses the OpenTelemetry Metrics API directly (`metrics.get_meter("evaluationimprovement")`)
— real behavior is wired once at the composition root by infrastructure.observability,
this class only names instruments and records through the vendor-neutral API, so it
stays a plain application-layer collaborator (no import-linter violation).
"""

from __future__ import annotations

from opentelemetry import metrics

_meter = metrics.get_meter("evaluationimprovement")


class EvaluationTelemetry:
    def __init__(self) -> None:
        self._run_total = _meter.create_counter("evaluation_run_total", description="Evaluation runs by terminal status")
        self._case_total = _meter.create_counter("evaluation_case_total", description="Evaluation cases graded, by result/criticality")
        self._gate_pass_total = _meter.create_counter("evaluation_gate_pass_total", description="Release gate evaluations that passed")
        self._gate_fail_total = _meter.create_counter("evaluation_gate_fail_total", description="Release gate evaluations that failed, by reason")
        self._regression_total = _meter.create_counter("evaluation_regression_total", description="Regressions detected, by dimension/severity")
        self._candidate_total = _meter.create_counter("improvement_candidate_total", description="Improvement candidates, by type/status/risk_level")
        self._canary_rollback_total = _meter.create_counter("canary_rollback_total", description="Canary rollbacks, by reason")
        self._grader_error_total = _meter.create_counter("grader_error_total", description="Grader invocations that raised GRADER_ERROR")
        self._judge_calibration_drift_total = _meter.create_counter(
            "judge_calibration_drift_total", description="Judge calibration checks that found drift exceeding threshold",
        )
        # SPEC-EI-028 (online-sample-evaluation) / 04-use-cases UC-EI-006 step 5:
        # "输出 trend metric，不直接阻塞业务链路" — a histogram (not a pass/fail counter),
        # since this is a quality trend signal, never a gate.
        self._online_sample_quality_score = _meter.create_histogram(
            "online_sample_quality_score", description="Delayed quality score of a scored online sample, by source_event_type",
        )
        # SPEC-EI-033 (observability-evaluation-signal-contract) / 12-observability
        # §"Metrics": three instruments this domain's own LLD names that nothing wired
        # until this spec — `evaluation_score`, `evaluation_cost_tokens_total`,
        # `evaluation_latency_seconds`.
        self._evaluation_score = _meter.create_histogram("evaluation_score", description="Per-dimension score, by dimension/dataset/target_version")
        self._evaluation_cost_tokens_total = _meter.create_counter(
            "evaluation_cost_tokens_total", description="Agent runtime token cost observed during case execution, by model/target_version",
        )
        self._evaluation_latency_seconds = _meter.create_histogram(
            "evaluation_latency_seconds", description="Wall-clock duration of one pipeline stage, by stage",
        )

    def record_online_sample_scored(self, source_event_type: str, composite_score: float) -> None:
        self._online_sample_quality_score.record(composite_score, {"source_event_type": source_event_type})

    def record_score(self, dimension: str, dataset_version: str, target_version: str, score: float) -> None:
        self._evaluation_score.record(score, {"dimension": dimension, "dataset": dataset_version, "target_version": target_version})

    def record_cost_tokens(self, model: str, target_version: str, tokens: int) -> None:
        self._evaluation_cost_tokens_total.add(tokens, {"model": model, "target_version": target_version})

    def record_stage_latency(self, stage: str, seconds: float) -> None:
        self._evaluation_latency_seconds.record(seconds, {"stage": stage})

    def record_run_status(self, status: str, dataset: str, target_version: str) -> None:
        self._run_total.add(1, {"status": status, "dataset": dataset, "target_version": target_version})

    def record_case_result(self, result: str, criticality: str) -> None:
        self._case_total.add(1, {"result": result, "criticality": criticality})

    def record_gate_passed(self, gate_policy: str) -> None:
        self._gate_pass_total.add(1, {"gate_policy": gate_policy})

    def record_gate_failed(self, gate_policy: str, reason: str) -> None:
        self._gate_fail_total.add(1, {"gate_policy": gate_policy, "reason": reason})

    def record_regression(self, dimension: str, severity: str) -> None:
        self._regression_total.add(1, {"dimension": dimension, "severity": severity})

    def record_candidate(self, candidate_type: str, status: str, risk_level: str) -> None:
        self._candidate_total.add(1, {"type": candidate_type, "status": status, "risk_level": risk_level})

    def record_canary_rollback(self, reason: str) -> None:
        self._canary_rollback_total.add(1, {"reason": reason})

    def record_grader_error(self, grader_type: str, grader_version: str) -> None:
        self._grader_error_total.add(1, {"grader_type": grader_type, "grader_version": grader_version})

    def record_judge_calibration_drift(self, grader_version: str) -> None:
        self._judge_calibration_drift_total.add(1, {"grader_version": grader_version})
