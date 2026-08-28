"""01-domain-model §"EvaluationScore". 02-business-invariants INV-EI-007: "Evaluation
result 只能 append 或 supersede，不能静默改写历史 score/report" — EvaluationScore is frozen
and has no update method; a re-grade creates a new row and the repository marks the
prior one non-active (see ScoreRepository.save()'s own docstring in ports_out.py).
"""

from __future__ import annotations

import dataclasses

from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, ScoreFailureCode
from evaluationimprovement.domain.ids import RunId, ScoreId, TestCaseId
from evaluationimprovement.domain.values import EvidenceRef


@dataclasses.dataclass(frozen=True, slots=True)
class EvaluationScore:
    score_id: ScoreId
    run_id: RunId
    test_case_id: TestCaseId
    dimension: EvaluationDimension
    score: float
    passed: bool
    threshold: float
    grader_type: GraderType
    grader_version: str
    evidence_ref: EvidenceRef | None = None
    failure_code: ScoreFailureCode | None = None
    details: dict[str, object] = dataclasses.field(default_factory=dict)
    is_active: bool = True

    @staticmethod
    def create(
        score_id: ScoreId, run_id: RunId, test_case_id: TestCaseId, dimension: EvaluationDimension, score: float,
        threshold: float, grader_type: GraderType, grader_version: str, evidence_ref: EvidenceRef | None = None,
        failure_code: ScoreFailureCode | None = None, details: dict[str, object] | None = None,
    ) -> "EvaluationScore":
        if not grader_version or not grader_version.strip():
            raise ValueError("graderVersion must not be blank")
        passed = failure_code is None and score >= threshold
        return EvaluationScore(
            score_id=score_id, run_id=run_id, test_case_id=test_case_id, dimension=dimension, score=score,
            passed=passed, threshold=threshold, grader_type=grader_type, grader_version=grader_version,
            evidence_ref=evidence_ref, failure_code=failure_code, details=details or {},
        )

    @staticmethod
    def graded_error(
        score_id: ScoreId, run_id: RunId, test_case_id: TestCaseId, dimension: EvaluationDimension, threshold: float,
        grader_type: GraderType, grader_version: str, reason: str,
    ) -> "EvaluationScore":
        """10-failure-handling §"Grader Failure": "Deterministic grader failure：对应
        dimension 标记 GRADER_ERROR，critical dimension 导致 gate failed."
        """
        return EvaluationScore(
            score_id=score_id, run_id=run_id, test_case_id=test_case_id, dimension=dimension, score=0.0, passed=False,
            threshold=threshold, grader_type=grader_type, grader_version=grader_version,
            failure_code=ScoreFailureCode.GRADER_ERROR, details={"reason": reason},
        )

    def superseded(self) -> "EvaluationScore":
        return dataclasses.replace(self, is_active=False)
