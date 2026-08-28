from __future__ import annotations

from datetime import UTC, datetime

import pytest

from evaluationimprovement.domain.ids import ReportId, RunId
from evaluationimprovement.domain.regression_report import RegressionReport
from evaluationimprovement.domain.values import GateResult

_NOW = datetime.now(UTC)


@pytest.mark.unit
def test_overall_decision_is_passed_when_nothing_failed() -> None:
    report = RegressionReport.create(
        ReportId.new_id(), RunId.new_id(), None, metric_diffs=(), gate_results=(GateResult("policy_violation_zero", True),),
        critical_failures=(), recommendation="eligible", now=_NOW,
    )
    assert report.overall_decision.value == "PASSED"


@pytest.mark.unit
def test_a_single_critical_failure_forces_gate_failed() -> None:
    """02-business-invariants INV-EI-008: "Critical case 任一失败时，release gate 必须
    失败" — enforced by construction, not left to the caller to remember.
    """
    report = RegressionReport.create(
        ReportId.new_id(), RunId.new_id(), None, metric_diffs=(), gate_results=(), critical_failures=("case-1",),
        recommendation="block", now=_NOW,
    )
    assert report.overall_decision.value == "FAILED"


@pytest.mark.unit
def test_a_failed_gate_result_forces_gate_failed_even_with_no_critical_failures() -> None:
    report = RegressionReport.create(
        ReportId.new_id(), RunId.new_id(), None, metric_diffs=(), gate_results=(GateResult("forbidden_tool_zero", False),),
        critical_failures=(), recommendation="block", now=_NOW,
    )
    assert report.overall_decision.value == "FAILED"
