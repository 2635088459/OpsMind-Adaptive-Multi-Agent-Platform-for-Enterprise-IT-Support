"""01-domain-model §"RegressionReport". 02-business-invariants INV-EI-007: reports are
append-only, never mutated in place.
"""

from __future__ import annotations

import dataclasses
from datetime import datetime

from evaluationimprovement.domain.enums import GateDecision
from evaluationimprovement.domain.ids import ReportId, RunId
from evaluationimprovement.domain.values import GateResult, MetricDiff


@dataclasses.dataclass(frozen=True, slots=True)
class RegressionReport:
    report_id: ReportId
    run_id: RunId
    baseline_run_id: RunId | None
    overall_decision: GateDecision
    metric_diffs: tuple[MetricDiff, ...]
    gate_results: tuple[GateResult, ...]
    critical_failures: tuple[str, ...]
    recommendation: str
    created_at: datetime

    @staticmethod
    def create(
        report_id: ReportId, run_id: RunId, baseline_run_id: RunId | None, metric_diffs: tuple[MetricDiff, ...],
        gate_results: tuple[GateResult, ...], critical_failures: tuple[str, ...], recommendation: str, now: datetime,
    ) -> "RegressionReport":
        """02-business-invariants INV-EI-008: "Critical case 任一失败时，release gate
        必须失败" — the overall decision is derived, never passed in independently, so a
        caller cannot accidentally mark PASSED while critical_failures is non-empty or
        any gate_result failed.
        """
        overall_decision = (
            GateDecision.FAILED if critical_failures or any(not g.passed for g in gate_results) else GateDecision.PASSED
        )
        return RegressionReport(
            report_id=report_id, run_id=run_id, baseline_run_id=baseline_run_id, overall_decision=overall_decision,
            metric_diffs=metric_diffs, gate_results=gate_results, critical_failures=critical_failures,
            recommendation=recommendation, created_at=now,
        )
