from __future__ import annotations

import pytest

from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, ScoreFailureCode
from evaluationimprovement.domain.ids import RunId, ScoreId, TestCaseId
from evaluationimprovement.domain.score import EvaluationScore


@pytest.mark.unit
def test_score_meeting_threshold_passes() -> None:
    score = EvaluationScore.create(
        ScoreId.new_id(), RunId.new_id(), TestCaseId.new_id(), EvaluationDimension.CLASSIFICATION_ACCURACY, 1.0, 1.0,
        GraderType.DETERMINISTIC, "v1",
    )
    assert score.passed is True


@pytest.mark.unit
def test_score_below_threshold_fails() -> None:
    score = EvaluationScore.create(
        ScoreId.new_id(), RunId.new_id(), TestCaseId.new_id(), EvaluationDimension.CLASSIFICATION_ACCURACY, 0.5, 1.0,
        GraderType.DETERMINISTIC, "v1",
    )
    assert score.passed is False


@pytest.mark.unit
def test_a_failure_code_always_fails_regardless_of_score_value() -> None:
    """10-failure-handling §"Grader Failure": GRADER_ERROR/UNSCORED never count as a
    pass even if the raw score value happens to clear the threshold.
    """
    score = EvaluationScore.create(
        ScoreId.new_id(), RunId.new_id(), TestCaseId.new_id(), EvaluationDimension.CLASSIFICATION_ACCURACY, 1.0, 1.0,
        GraderType.DETERMINISTIC, "v1", failure_code=ScoreFailureCode.GRADER_ERROR,
    )
    assert score.passed is False


@pytest.mark.unit
def test_superseded_score_is_no_longer_active() -> None:
    score = EvaluationScore.create(
        ScoreId.new_id(), RunId.new_id(), TestCaseId.new_id(), EvaluationDimension.CLASSIFICATION_ACCURACY, 1.0, 1.0,
        GraderType.DETERMINISTIC, "v1",
    )
    assert score.is_active is True
    assert score.superseded().is_active is False
