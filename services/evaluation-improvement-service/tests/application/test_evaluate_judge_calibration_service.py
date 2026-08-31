"""SPEC-EI-018 (judge-calibration-drift-guard): EvaluateJudgeCalibrationService —
mean-absolute-error drift computation and JudgeBundleStatus persistence, exercised
against the real container (in-memory JudgeBundleStatusRepository).
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.records import CaseExecutionResult, JudgeCalibrationCase
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CaseExecutionStatus, Criticality, GraderType, ScoreFailureCode
from evaluationimprovement.domain.ids import DatasetId, TestCaseId
from evaluationimprovement.domain.test_case import EvaluationTestCase


class _FakeGraderResult:
    def __init__(self, score: float, failure_code=None) -> None:  # noqa: ANN001
        self.score = score
        self.failure_code = failure_code


class _FakeJudge:
    """A duck-typed grader — same shape infrastructure.graders.llm_judge's own
    AnthropicQualityJudge/ExplanationQualityJudge expose (`.version` + `.grade()`).
    """

    def __init__(self, version: str, scores: list[float] | None = None, always_fails: bool = False) -> None:
        self.version = version
        self._scores = scores or []
        self._always_fails = always_fails
        self.call_count = 0

    def grade(self, test_case, result):  # noqa: ANN001, ANN201, ARG002
        self.call_count += 1
        if self._always_fails:
            return _FakeGraderResult(0.0, failure_code=ScoreFailureCode.UNSCORED)
        return _FakeGraderResult(self._scores[self.call_count - 1])


def _calibration_case(expected_score: float) -> JudgeCalibrationCase:
    test_case = EvaluationTestCase.create(
        TestCaseId.new_id(), DatasetId.new_id(), "k1", "s", "", {}, {"classification": "X"}, (), (), False, {}, Criticality.STANDARD,
    )
    result = CaseExecutionResult(
        run_id="run-1", test_case_id=str(test_case.test_case_id), run_generation=1, final_state="RESOLVED",
        tool_calls=(), classification="X", policy_violation_count=0, forbidden_tool_call_count=0,
        unauthorized_memory_access_count=0, cost_tokens=0, latency_ms=0, workflow_trace_ref="",
        status=CaseExecutionStatus.COMPLETED,
    )
    return JudgeCalibrationCase(test_case=test_case, result=result, expected_score=expected_score)


@pytest.mark.unit
def test_low_drift_keeps_the_bundle_enabled(container: Container) -> None:
    judge = _FakeJudge("judge-v1", scores=[0.82, 0.91])
    cases = (_calibration_case(0.8), _calibration_case(0.9))

    status = container.evaluate_judge_calibration_service.evaluate(judge, cases)
    assert status.enabled is True
    assert status.grader_version == "judge-v1"
    assert status.last_mean_absolute_error == pytest.approx((0.02 + 0.01) / 2, abs=1e-9)

    found = container.judge_bundle_status_repository.find_status("judge-v1")
    assert found is not None
    assert found.enabled is True


@pytest.mark.unit
def test_high_drift_disables_the_bundle(container: Container) -> None:
    judge = _FakeJudge("judge-v2", scores=[0.1, 0.05])
    cases = (_calibration_case(0.9), _calibration_case(0.95))

    status = container.evaluate_judge_calibration_service.evaluate(judge, cases, drift_threshold=0.15)
    assert status.enabled is False
    assert status.disabled_reason is not None
    assert "drift" in status.disabled_reason

    found = container.judge_bundle_status_repository.find_status("judge-v2")
    assert found is not None
    assert found.enabled is False


@pytest.mark.unit
def test_a_failing_judge_call_counts_as_maximum_deviation(container: Container) -> None:
    judge = _FakeJudge("judge-v3", always_fails=True)
    cases = (_calibration_case(0.9),)
    status = container.evaluate_judge_calibration_service.evaluate(judge, cases)
    assert status.enabled is False
    assert status.last_mean_absolute_error == 1.0


@pytest.mark.unit
def test_empty_calibration_set_is_rejected(container: Container) -> None:
    judge = _FakeJudge("judge-v4")
    with pytest.raises(ValueError, match="must not be empty"):
        container.evaluate_judge_calibration_service.evaluate(judge, ())


@pytest.mark.unit
def test_grader_registry_short_circuits_to_unscored_without_calling_a_disabled_judge(container: Container) -> None:
    from evaluationimprovement.domain.enums import EvaluationDimension
    from evaluationimprovement.infrastructure.graders.registry import GraderRegistry

    judge = _FakeJudge("disabled-judge-v1", scores=[1.0])
    container.evaluate_judge_calibration_service.evaluate(judge, (_calibration_case(0.0),), drift_threshold=0.0)
    assert container.judge_bundle_status_repository.find_status("disabled-judge-v1").enabled is False

    registry = GraderRegistry(quality_judge=judge, judge_bundle_status_repository=container.judge_bundle_status_repository)
    test_case = _calibration_case(0.5).test_case
    result = _calibration_case(0.5).result
    grader_result = registry.grade(EvaluationDimension.HANDOFF_COMPLETENESS, GraderType.LLM_JUDGE, test_case, result)

    assert grader_result.failure_code == ScoreFailureCode.UNSCORED
    assert "disabled" in grader_result.details["reason"]
    # The gate short-circuited before ever calling grade() a second time — only the
    # one call evaluate() itself made during calibration.
    assert judge.call_count == 1
