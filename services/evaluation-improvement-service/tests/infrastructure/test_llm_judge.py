"""SPEC-EI-016 (quality-llm-judge-graders). AnthropicQualityJudge types its own
`client` param as `object` (never `anthropic.Anthropic`) precisely so it is testable
against a duck-typed fake here without the real package installed or a network call —
see that class's own module docstring.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.domain.enums import Criticality, GraderType, ScoreFailureCode
from evaluationimprovement.domain.ids import DatasetId, TestCaseId
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.infrastructure.graders.llm_judge import AnthropicQualityJudge, ExplanationQualityJudge, JudgeAssessment


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


def _test_case() -> EvaluationTestCase:
    return EvaluationTestCase.create(
        TestCaseId.new_id(), DatasetId.new_id(), "k1", "Duo enrollment expired", "mfa broken",
        {}, {"classification": "MFA_ENROLLMENT_EXPIRED"}, ("reset_duo_enrollment",), (), False, {}, Criticality.CRITICAL,
    )


def _result(**overrides):
    from evaluationimprovement.application.records import CaseExecutionResult
    from evaluationimprovement.domain.enums import CaseExecutionStatus

    defaults = dict(
        run_id="run-1", test_case_id="case-1", run_generation=1, final_state="RESOLVED",
        tool_calls=("reset_duo_enrollment",), classification="MFA_ENROLLMENT_EXPIRED", policy_violation_count=0,
        forbidden_tool_call_count=0, unauthorized_memory_access_count=0, cost_tokens=100, latency_ms=500,
        workflow_trace_ref="trace-1", status=CaseExecutionStatus.COMPLETED, explanation_text="Reset the Duo enrollment.",
    )
    defaults.update(overrides)
    return CaseExecutionResult(**defaults)


@pytest.mark.unit
def test_placeholder_judge_is_always_unscored() -> None:
    judge = ExplanationQualityJudge()
    result = judge.grade(_test_case(), _result())
    assert result.grader_type == GraderType.LLM_JUDGE
    assert result.failure_code == ScoreFailureCode.UNSCORED
    assert result.score == 0.0


@pytest.mark.unit
def test_anthropic_judge_averages_the_four_dimensions() -> None:
    assessment = JudgeAssessment(
        explanation_quality=0.9, evidence_grounding=0.8, handoff_completeness=1.0, user_instruction_clarity=0.7,
        reasoning="Clear and grounded.",
    )
    client = _FakeAnthropicClient(assessment=assessment)
    judge = AnthropicQualityJudge(client, "claude-opus-5")

    result = judge.grade(_test_case(), _result())
    assert result.grader_type == GraderType.LLM_JUDGE
    assert result.failure_code is None
    assert result.score == pytest.approx((0.9 + 0.8 + 1.0 + 0.7) / 4.0)
    assert result.details["reasoning"] == "Clear and grounded."

    # Ground truth is deliberately included in the judge prompt — unlike the real
    # runtime execution client, a judge's whole job is comparing against it.
    call = client.messages.last_call
    assert call is not None
    assert "MFA_ENROLLMENT_EXPIRED" in call["messages"][0]["content"]


@pytest.mark.unit
def test_anthropic_judge_fails_open_to_unscored_on_error() -> None:
    client = _FakeAnthropicClient(raise_error=True)
    judge = AnthropicQualityJudge(client, "claude-opus-5")
    result = judge.grade(_test_case(), _result())
    assert result.failure_code == ScoreFailureCode.UNSCORED
    assert result.score == 0.0
