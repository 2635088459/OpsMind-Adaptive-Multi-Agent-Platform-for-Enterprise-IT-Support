"""SPEC-EI-028 (online-sample-evaluation). AnthropicOnlineSampleJudge mirrors
AnthropicQualityJudge's own duck-typed `client: object` testability exactly — see
tests/infrastructure/test_llm_judge.py's own module docstring.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from evaluationimprovement.application.records import OnlineEvaluationSample
from evaluationimprovement.domain.enums import GraderType, OnlineSampleStatus, ScoreFailureCode
from evaluationimprovement.infrastructure.graders.llm_judge import (
    AnthropicOnlineSampleJudge,
    JudgeAssessment,
    PlaceholderOnlineSampleJudge,
)


class _FakeParseResponse:
    def __init__(self, assessment: JudgeAssessment) -> None:
        self.parsed_output = assessment


class _FakeMessages:
    def __init__(self, assessment: JudgeAssessment | None, raise_error: bool) -> None:
        self._assessment = assessment
        self._raise_error = raise_error
        self.last_call: dict | None = None

    def parse(self, **kwargs):  # noqa: ANN003, ANN201
        self.last_call = kwargs
        if self._raise_error:
            raise RuntimeError("anthropic api unreachable")
        return _FakeParseResponse(self._assessment)


class _FakeAnthropicClient:
    def __init__(self, assessment: JudgeAssessment | None = None, raise_error: bool = False) -> None:
        self.messages = _FakeMessages(assessment, raise_error)


def _sample(**overrides) -> OnlineEvaluationSample:
    defaults = dict(
        sample_id=uuid.uuid4(), candidate_id=None, target_version="agent-runtime:rc1",
        source_event_type="WORKFLOW_COMPLETED", source_trace_ref="trace-redacted-1",
        redacted_context={"summary": "Reset the Duo enrollment for the user."}, status=OnlineSampleStatus.QUEUED,
        collected_at=datetime.now(UTC),
    )
    defaults.update(overrides)
    return OnlineEvaluationSample(**defaults)


@pytest.mark.unit
def test_placeholder_judge_is_always_unscored() -> None:
    judge = PlaceholderOnlineSampleJudge()
    result = judge.grade(_sample())
    assert result.grader_type == GraderType.LLM_JUDGE
    assert result.failure_code == ScoreFailureCode.UNSCORED
    assert result.score == 0.0


@pytest.mark.unit
def test_anthropic_judge_averages_the_four_dimensions_and_never_sends_ground_truth() -> None:
    assessment = JudgeAssessment(
        explanation_quality=0.9, evidence_grounding=0.8, handoff_completeness=1.0, user_instruction_clarity=0.7,
        reasoning="Clear and grounded.",
    )
    client = _FakeAnthropicClient(assessment=assessment)
    judge = AnthropicOnlineSampleJudge(client, "claude-opus-5")

    result = judge.grade(_sample())
    assert result.grader_type == GraderType.LLM_JUDGE
    assert result.failure_code is None
    assert result.score == pytest.approx((0.9 + 0.8 + 1.0 + 0.7) / 4.0)
    assert result.details["reasoning"] == "Clear and grounded."

    # A production trace has no ground truth to leak in the first place — unlike
    # AnthropicQualityJudge's own case-grading prompt, which deliberately does
    # include one.
    call = client.messages.last_call
    assert call is not None
    assert "groundTruth" not in call["messages"][0]["content"]
    assert "Reset the Duo enrollment" in call["messages"][0]["content"]


@pytest.mark.unit
def test_anthropic_judge_fails_open_to_unscored_on_error() -> None:
    client = _FakeAnthropicClient(raise_error=True)
    judge = AnthropicOnlineSampleJudge(client, "claude-opus-5")
    result = judge.grade(_sample())
    assert result.failure_code == ScoreFailureCode.UNSCORED
    assert result.score == 0.0
