"""13-package-and-class-design §"Grader Registry" names four LLM Judge graders
(ExplanationQualityJudge, EvidenceGroundingJudge, HandoffCompletenessJudge,
UserInstructionClarityJudge) — their real prompted-judge implementations are
SPEC-EI-016 (quality-llm-judge-graders) scope. SPEC-EI-001 ships one honestly-labeled
placeholder that never calls a real LLM and always reports UNSCORED, proving the
registry can carry an LLM_JUDGE-typed grader without that grader ever being able to
influence a safety gate — 02-business-invariants INV-EI-003: "安全相关指标必须使用
deterministic grader 判定；LLM Judge 只能用于质量类辅助评分." EvaluateReleaseGateService
only ever reads DETERMINISTIC-typed scores for gate decisions, so this placeholder's
UNSCORED output is structurally inert for gating even before that rule is applied.
"""

from __future__ import annotations

from evaluationimprovement.application.records import CaseExecutionResult, GraderResult
from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, ScoreFailureCode
from evaluationimprovement.domain.test_case import EvaluationTestCase


class ExplanationQualityJudge:
    """Placeholder — never calls a real LLM. Always returns UNSCORED so no caller can
    mistake this for a real quality assessment. Real judge prompting is SPEC-EI-016.
    """

    dimension = EvaluationDimension.HANDOFF_COMPLETENESS
    version = "explanation-quality-judge-placeholder-v0"

    def grade(self, test_case: EvaluationTestCase, result: CaseExecutionResult) -> GraderResult:  # noqa: ARG002
        return GraderResult(
            dimension=self.dimension, score=0.0, threshold=0.0, grader_type=GraderType.LLM_JUDGE,
            grader_version=self.version, failure_code=ScoreFailureCode.UNSCORED,
            details={"reason": "real LLM Judge grading is SPEC-EI-016 scope; this is a placeholder"},
        )
