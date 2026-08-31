"""13-package-and-class-design `infrastructure/graders/registry.py`. Satisfies
application.ports_out.GraderRegistryPort — the only way application code reaches a
grader (the import-linter "application must not depend on infrastructure" contract).
"""

from __future__ import annotations

import logging

from opentelemetry import trace

from evaluationimprovement.application.exceptions import GraderNotFoundException
from evaluationimprovement.application.records import CaseExecutionResult, GraderResult
from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, ScoreFailureCode
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.infrastructure.graders.deterministic import (
    ClassificationAccuracyGrader,
    PolicyComplianceGrader,
    ResolutionSuccessGrader,
    RootCauseMatchGrader,
    ToolAllowlistGrader,
    ToolArgumentSchemaGrader,
)
from evaluationimprovement.infrastructure.graders.llm_judge import ExplanationQualityJudge

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


class GraderRegistry:
    """SPEC-EI-001 shipped two DETERMINISTIC graders and one LLM_JUDGE placeholder.
    SPEC-EI-014/015 register the rest of 13-package-and-class-design's own named
    catalog (see infrastructure.graders.deterministic's own module docstring for how
    nine LLD-named classes map onto six actual EvaluationDimension slots).
    SPEC-EI-016 replaces the LLM_JUDGE placeholder registration. SPEC-EI-018 adds the
    calibration-drift gate `grade()` checks before ever invoking an LLM_JUDGE grader.
    """

    def __init__(self, quality_judge: object | None = None, judge_bundle_status_repository: object | None = None) -> None:
        """`quality_judge` defaults to the SPEC-EI-001 placeholder; container.py
        passes infrastructure.graders.llm_judge.AnthropicQualityJudge instead when
        Settings.llm_judge_mode="anthropic" (see container.py's own
        `_build_quality_judge()`) — mirrors every other real/fake adapter swap in this
        service (agent_runtime_evaluation_mode, langsmith_mode). `judge_bundle_status_
        repository` (SPEC-EI-018, kept untyped for the same reason `quality_judge` is
        — see application.ports_out.JudgeBundleStatusRepository) defaults to None,
        which `grade()` treats as "never disabled" so every test that never wired one
        keeps working unchanged.
        """
        judge = quality_judge or ExplanationQualityJudge()
        self._judge_bundle_status_repository = judge_bundle_status_repository
        self._graders = {
            (ClassificationAccuracyGrader.dimension, GraderType.DETERMINISTIC): ClassificationAccuracyGrader(),
            (ToolAllowlistGrader.dimension, GraderType.DETERMINISTIC): ToolAllowlistGrader(),
            (RootCauseMatchGrader.dimension, GraderType.DETERMINISTIC): RootCauseMatchGrader(),
            (PolicyComplianceGrader.dimension, GraderType.DETERMINISTIC): PolicyComplianceGrader(),
            (ResolutionSuccessGrader.dimension, GraderType.DETERMINISTIC): ResolutionSuccessGrader(),
            (ToolArgumentSchemaGrader.dimension, GraderType.DETERMINISTIC): ToolArgumentSchemaGrader(),
            (ExplanationQualityJudge.dimension, GraderType.LLM_JUDGE): judge,
        }

    def grade(
        self, dimension: EvaluationDimension, grader_type: GraderType, test_case: EvaluationTestCase, result: CaseExecutionResult,
    ) -> GraderResult:
        with tracer.start_as_current_span("GraderRegistry.grade"):
            return self._grade_traced(dimension, grader_type, test_case, result)

    def _grade_traced(
        self, dimension: EvaluationDimension, grader_type: GraderType, test_case: EvaluationTestCase, result: CaseExecutionResult,
    ) -> GraderResult:
        grader = self._graders.get((dimension, grader_type))
        if grader is None:
            raise GraderNotFoundException(dimension, grader_type)
        if grader_type == GraderType.LLM_JUDGE and self._judge_bundle_status_repository is not None:
            # SPEC-EI-018 / 10-failure-handling §"Judge drift": "同一 judge bundle 对固定
            # calibration set 超出阈值时禁用该 bundle" — checked before ever invoking the
            # judge, never after, so a drifted bundle spends no tokens either.
            status = self._judge_bundle_status_repository.find_status(grader.version)
            if status is not None and not status.enabled:
                return GraderResult(
                    dimension=dimension, score=0.0, threshold=0.0, grader_type=grader_type, grader_version=grader.version,
                    failure_code=ScoreFailureCode.UNSCORED, details={"reason": f"judge bundle disabled: {status.disabled_reason}"},
                )
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
        """Only requests dimensions this registry can actually grade meaningfully for
        a given case. CLASSIFICATION_ACCURACY (has `groundTruth["classification"]`)
        and TOOL_SELECTION (has any allowed/forbidden tool declared) were
        SPEC-EI-001 scope. SPEC-EI-014/015 add: POLICY_COMPLIANCE and
        RESOLUTION_SUCCESS unconditionally (every case implicitly must comply with
        policy/approval rules and reach a genuinely verified resolution, regardless of
        what else it declares); ROOT_CAUSE_ACCURACY only when the dataset author
        supplied a `rootCause` distinct from `classification` (see
        RootCauseMatchGrader's own docstring for why re-asking with the fallback would
        just duplicate ClassificationAccuracyGrader); TOOL_ARGUMENTS only when
        `expectedToolArgs` is non-empty (mirrors TOOL_SELECTION's own
        "nothing declared, nothing to check" precedent). SPEC-EI-016 adds
        HANDOFF_COMPLETENESS.
        """
        pairs: list[tuple[EvaluationDimension, GraderType]] = []
        if "classification" in test_case.ground_truth:
            pairs.append((EvaluationDimension.CLASSIFICATION_ACCURACY, GraderType.DETERMINISTIC))
        if test_case.allowed_tools or test_case.forbidden_tools:
            pairs.append((EvaluationDimension.TOOL_SELECTION, GraderType.DETERMINISTIC))
        if "rootCause" in test_case.ground_truth:
            pairs.append((EvaluationDimension.ROOT_CAUSE_ACCURACY, GraderType.DETERMINISTIC))
        pairs.append((EvaluationDimension.POLICY_COMPLIANCE, GraderType.DETERMINISTIC))
        pairs.append((EvaluationDimension.RESOLUTION_SUCCESS, GraderType.DETERMINISTIC))
        if test_case.ground_truth.get("expectedToolArgs"):
            pairs.append((EvaluationDimension.TOOL_ARGUMENTS, GraderType.DETERMINISTIC))
        pairs.append((EvaluationDimension.HANDOFF_COMPLETENESS, GraderType.LLM_JUDGE))
        return tuple(pairs)

    def list_registered(self) -> tuple[tuple[str, GraderType, EvaluationDimension, str], ...]:
        """05-api-contracts §"管理 API": `GET /evaluation/graders`. Introspects the
        actual `self._graders` mapping instead of a hand-maintained parallel list —
        this module's own earlier SPEC-EI-001 scope left
        application.services.grader_catalog.GraderCatalogService keeping its own
        static copy in sync by hand (see that module's own docstring, which named
        this exact gap), which also could not reflect which LLM_JUDGE adapter is
        actually active. `(class name, grader_type, dimension, grader_version)` per
        entry — `type(grader).__name__` doubles as the LLD's own named-grader
        identity even where several LLD names share one dimension slot (see this
        module's own docstring for the fold).
        """
        return tuple(
            (type(grader).__name__, grader_type, dimension, grader.version)
            for (dimension, grader_type), grader in self._graders.items()
        )
