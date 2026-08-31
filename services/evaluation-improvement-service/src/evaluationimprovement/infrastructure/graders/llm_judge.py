"""13-package-and-class-design §"Grader Registry" names four LLM Judge graders
(ExplanationQualityJudge, EvidenceGroundingJudge, HandoffCompletenessJudge,
UserInstructionClarityJudge). SPEC-EI-001 shipped ExplanationQualityJudge as an
honestly-labeled placeholder — never calls a real LLM, always UNSCORED — proving the
registry can carry an LLM_JUDGE-typed grader without it ever being able to influence a
safety gate (02-business-invariants INV-EI-003: "安全相关指标必须使用 deterministic grader
判定；LLM Judge 只能用于质量类辅助评分." EvaluateReleaseGateService/CompareRegressionService
only ever read DETERMINISTIC-typed scores, so an UNSCORED placeholder is structurally
inert for gating even before that rule is applied).

SPEC-EI-016 adds AnthropicQualityJudge, the real judge — folding all four LLD-named
classes into one composite HANDOFF_COMPLETENESS assessment (EvaluationDimension has
one quality-judge slot, the same folding infrastructure.graders.deterministic's own
module docstring already documents for the deterministic catalog), scored via a real
`anthropic` API call using structured outputs (`client.messages.parse()` +
`JudgeAssessment`). Unlike infrastructure.runtime.agent_runtime_client's own real
HTTP adapter — which must never leak ground truth to the system under test — a judge's
entire job is comparing an attempt against ground truth, so this prompt deliberately
includes it.
"""

from __future__ import annotations

import logging

from pydantic import BaseModel, Field

from evaluationimprovement.application.records import CaseExecutionResult, GraderResult, OnlineEvaluationSample
from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, ScoreFailureCode
from evaluationimprovement.domain.test_case import EvaluationTestCase

logger = logging.getLogger("evaluationimprovement.infrastructure.graders")

_JUDGE_SYSTEM_PROMPT = (
    "You are a strict quality judge for an IT-support AI agent's completed case. "
    "You are grading QUALITY only — never a safety or policy decision; those are "
    "decided by deterministic checks elsewhere. Score each dimension from 0.0 (very "
    "poor) to 1.0 (excellent), based only on the evidence given."
)


class JudgeAssessment(BaseModel):
    explanation_quality: float = Field(ge=0.0, le=1.0, description="Is the agent's explanation clear, accurate, and complete?")
    evidence_grounding: float = Field(ge=0.0, le=1.0, description="Is the explanation grounded in the actual tool calls/final state, not invented?")
    handoff_completeness: float = Field(ge=0.0, le=1.0, description="Would a human agent picking this up have everything they need?")
    user_instruction_clarity: float = Field(ge=0.0, le=1.0, description="Would the end user understand what happened and what to do next?")
    reasoning: str = Field(description="One or two sentences of justification.")


class ExplanationQualityJudge:
    """Placeholder — never calls a real LLM. Always returns UNSCORED so no caller can
    mistake this for a real quality assessment. Real judge prompting is
    AnthropicQualityJudge (SPEC-EI-016).
    """

    dimension = EvaluationDimension.HANDOFF_COMPLETENESS
    version = "explanation-quality-judge-placeholder-v0"

    def grade(self, test_case: EvaluationTestCase, result: CaseExecutionResult) -> GraderResult:  # noqa: ARG002
        return GraderResult(
            dimension=self.dimension, score=0.0, threshold=0.0, grader_type=GraderType.LLM_JUDGE,
            grader_version=self.version, failure_code=ScoreFailureCode.UNSCORED,
            details={"reason": "real LLM Judge grading is SPEC-EI-016 scope; this is a placeholder"},
        )


class AnthropicQualityJudge:
    """SPEC-EI-016: the real judge, backed by the `anthropic` SDK's structured-output
    `messages.parse()`. `client` stays untyped (`object`) rather than `anthropic.
    Anthropic` — the same reason infrastructure.langsmith.dataset_adapter's own
    `client` param does — so this class is unit-testable against a duck-typed fake
    without the real package installed or a network call made.
    """

    dimension = EvaluationDimension.HANDOFF_COMPLETENESS
    version = "quality-judge-anthropic-v1"

    def __init__(self, client: object, model: str) -> None:
        self._client = client
        self._model = model

    def grade(self, test_case: EvaluationTestCase, result: CaseExecutionResult) -> GraderResult:
        try:
            response = self._client.messages.parse(
                model=self._model, max_tokens=1024, system=_JUDGE_SYSTEM_PROMPT,
                messages=[{"role": "user", "content": _build_prompt(test_case, result)}], output_format=JudgeAssessment,
            )
            assessment: JudgeAssessment = response.parsed_output
            composite = (
                assessment.explanation_quality + assessment.evidence_grounding + assessment.handoff_completeness
                + assessment.user_instruction_clarity
            ) / 4.0
            return GraderResult(
                dimension=self.dimension, score=composite, threshold=0.7, grader_type=GraderType.LLM_JUDGE,
                grader_version=self.version,
                details={
                    "explanationQuality": assessment.explanation_quality, "evidenceGrounding": assessment.evidence_grounding,
                    "handoffCompleteness": assessment.handoff_completeness,
                    "userInstructionClarity": assessment.user_instruction_clarity, "reasoning": assessment.reasoning,
                },
            )
        except Exception:
            # 10-failure-handling §"Grader Failure": "LLM Judge failure：质量类 dimension
            # 可标记 UNSCORED，但不能影响安全门禁" — this dimension is never read by any
            # gate/regression computation regardless (INV-EI-003), so failing open here
            # only ever costs an informational quality signal, never safety.
            logger.warning("LLM Judge call failed for test case %s", test_case.test_case_id, exc_info=True)
            return GraderResult(
                dimension=self.dimension, score=0.0, threshold=0.0, grader_type=GraderType.LLM_JUDGE,
                grader_version=self.version, failure_code=ScoreFailureCode.UNSCORED,
                details={"reason": "LLM Judge call failed"},
            )


class PlaceholderOnlineSampleJudge:
    """SPEC-EI-028 (online-sample-evaluation): the online-sample counterpart to
    ExplanationQualityJudge — never calls a real LLM, always UNSCORED. Default
    adapter every hermetic test relies on; real scoring is AnthropicOnlineSampleJudge.
    """

    dimension = EvaluationDimension.HANDOFF_COMPLETENESS
    version = "online-sample-quality-judge-placeholder-v0"

    def grade(self, sample: OnlineEvaluationSample) -> GraderResult:  # noqa: ARG002
        return GraderResult(
            dimension=self.dimension, score=0.0, threshold=0.0, grader_type=GraderType.LLM_JUDGE,
            grader_version=self.version, failure_code=ScoreFailureCode.UNSCORED,
            details={"reason": "real online-sample LLM Judge grading is SPEC-EI-028 opt-in scope; this is a placeholder"},
        )


class AnthropicOnlineSampleJudge:
    """SPEC-EI-028: the real online-sample judge. Reuses JudgeAssessment's own four
    facets (explanation_quality/evidence_grounding/handoff_completeness/
    user_instruction_clarity) unchanged — 04-use-cases UC-EI-006 step 4 names these
    exact four for online samples too — but builds its prompt from only
    `sample.redacted_context`/`source_trace_ref`, never a ground truth (a production
    trace has none; unlike AnthropicQualityJudge's own case-grading prompt, which
    deliberately does include it).
    """

    dimension = EvaluationDimension.HANDOFF_COMPLETENESS
    version = "online-sample-quality-judge-anthropic-v1"

    def __init__(self, client: object, model: str) -> None:
        self._client = client
        self._model = model

    def grade(self, sample: OnlineEvaluationSample) -> GraderResult:
        try:
            response = self._client.messages.parse(
                model=self._model, max_tokens=1024, system=_JUDGE_SYSTEM_PROMPT,
                messages=[{"role": "user", "content": _build_online_sample_prompt(sample)}], output_format=JudgeAssessment,
            )
            assessment: JudgeAssessment = response.parsed_output
            composite = (
                assessment.explanation_quality + assessment.evidence_grounding + assessment.handoff_completeness
                + assessment.user_instruction_clarity
            ) / 4.0
            return GraderResult(
                dimension=self.dimension, score=composite, threshold=0.7, grader_type=GraderType.LLM_JUDGE,
                grader_version=self.version,
                details={
                    "explanationQuality": assessment.explanation_quality, "evidenceGrounding": assessment.evidence_grounding,
                    "handoffCompleteness": assessment.handoff_completeness,
                    "userInstructionClarity": assessment.user_instruction_clarity, "reasoning": assessment.reasoning,
                },
            )
        except Exception:
            # 10-failure-handling §"Grader Failure" / UC-EI-006 step 5: "输出 trend
            # metric，不直接阻塞业务链路" — an online sample's quality signal is purely
            # informational, so a failed judge call fails open into UNSCORED exactly
            # like AnthropicQualityJudge's own case-grading path does.
            logger.warning("Online-sample LLM Judge call failed for sample %s", sample.sample_id, exc_info=True)
            return GraderResult(
                dimension=self.dimension, score=0.0, threshold=0.0, grader_type=GraderType.LLM_JUDGE,
                grader_version=self.version, failure_code=ScoreFailureCode.UNSCORED,
                details={"reason": "LLM Judge call failed"},
            )


def _build_online_sample_prompt(sample: OnlineEvaluationSample) -> str:
    return (
        f"Source event type: {sample.source_event_type}\n"
        f"Source trace reference: {sample.source_trace_ref}\n"
        f"Target version: {sample.target_version}\n"
        f"Redacted context: {sample.redacted_context}\n"
        f"---\n"
        f"This is a real production trace, not a labeled test case — there is no "
        f"ground truth to compare against. Judge the quality of the agent's own "
        f"explanation/handoff, as captured in the redacted context above, on its own "
        f"terms.\n"
    )


def _build_prompt(test_case: EvaluationTestCase, result: CaseExecutionResult) -> str:
    return (
        f"Scenario: {test_case.scenario}\n"
        f"User request (redacted): {test_case.user_request_redacted}\n"
        f"Ground truth: {test_case.ground_truth}\n"
        f"Verification condition: {test_case.verification_condition}\n"
        f"---\n"
        f"Agent's classification: {result.classification}\n"
        f"Agent's final state: {result.final_state}\n"
        f"Tool calls made: {list(result.tool_calls)}\n"
        f"Independent verification passed: {result.verification_passed}\n"
        f"Agent's explanation/handoff text: {result.explanation_text}\n"
    )
