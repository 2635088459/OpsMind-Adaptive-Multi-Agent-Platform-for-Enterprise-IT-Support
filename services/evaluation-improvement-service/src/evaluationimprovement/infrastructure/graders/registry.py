"""13-package-and-class-design `infrastructure/graders/registry.py`. Satisfies
application.ports_out.GraderRegistryPort — the only way application code reaches a
grader (the import-linter "application must not depend on infrastructure" contract).
"""

from __future__ import annotations

import logging

from evaluationimprovement.application.exceptions import GraderNotFoundException
from evaluationimprovement.application.records import CaseExecutionResult, GraderResult
from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, ScoreFailureCode
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.infrastructure.graders.deterministic import ClassificationAccuracyGrader, ToolAllowlistGrader
from evaluationimprovement.infrastructure.graders.llm_judge import ExplanationQualityJudge

logger = logging.getLogger(__name__)


class GraderRegistry:
    """SPEC-EI-001 scope registers exactly the graders infrastructure.graders.
    deterministic/llm_judge ship: two DETERMINISTIC graders and one LLM_JUDGE
    placeholder. Later specs (SPEC-EI-014/015/016) register the rest of
    13-package-and-class-design's own named catalog into the same registry shape.
    """

    def __init__(self) -> None:
        self._graders = {
            (ClassificationAccuracyGrader.dimension, GraderType.DETERMINISTIC): ClassificationAccuracyGrader(),
            (ToolAllowlistGrader.dimension, GraderType.DETERMINISTIC): ToolAllowlistGrader(),
            (ExplanationQualityJudge.dimension, GraderType.LLM_JUDGE): ExplanationQualityJudge(),
        }

    def grade(
        self, dimension: EvaluationDimension, grader_type: GraderType, test_case: EvaluationTestCase, result: CaseExecutionResult,
    ) -> GraderResult:
        grader = self._graders.get((dimension, grader_type))
        if grader is None:
            raise GraderNotFoundException(dimension, grader_type)
        try:
            return grader.grade(test_case, result)
        except Exception:
            # 10-failure-handling §"Grader Failure": "Deterministic grader failure：
            # 对应 dimension 标记 GRADER_ERROR."
            logger.exception("grader raised dimension=%s grader_type=%s", dimension, grader_type)
            return GraderResult(
                dimension=dimension, score=0.0, threshold=1.0, grader_type=grader_type, grader_version=grader.version,
                failure_code=ScoreFailureCode.GRADER_ERROR, details={},
            )

    def dimensions_for_case(self, test_case: EvaluationTestCase) -> tuple[tuple[EvaluationDimension, GraderType], ...]:
        """Only requests dimensions this registry can actually grade for real — a case
        with `groundTruth["classification"]` gets CLASSIFICATION_ACCURACY, and a case
        with any allowed/forbidden tool declared gets TOOL_SELECTION. Full per-case
        dimension coverage is SPEC-EI-014+ scope.
        """
        pairs: list[tuple[EvaluationDimension, GraderType]] = []
        if "classification" in test_case.ground_truth:
            pairs.append((EvaluationDimension.CLASSIFICATION_ACCURACY, GraderType.DETERMINISTIC))
        if test_case.allowed_tools or test_case.forbidden_tools:
            pairs.append((EvaluationDimension.TOOL_SELECTION, GraderType.DETERMINISTIC))
        return tuple(pairs)
