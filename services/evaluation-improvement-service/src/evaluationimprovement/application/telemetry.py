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
