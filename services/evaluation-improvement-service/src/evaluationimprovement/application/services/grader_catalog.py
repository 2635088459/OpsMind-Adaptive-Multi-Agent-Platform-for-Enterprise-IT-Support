"""Not among 13-package-and-class-design's ten named services — added the same way
audit_query.py was: 05-api-contracts §"管理 API" `GET /evaluation/graders` needs a real
read surface over infrastructure.graders.registry.GraderRegistry's own catalog,
reached only through GraderRegistryPort (application must not depend on
infrastructure).
"""

from __future__ import annotations

from evaluationimprovement.application.ports_out import GraderRegistryPort
from evaluationimprovement.application.views import GraderDescriptor
from evaluationimprovement.domain.enums import EvaluationDimension, GraderType

# SPEC-EI-001 only ever registers the three graders infrastructure.graders.registry
# ships (see that module's own docstring for why); this catalog is kept in sync with
# it by hand until SPEC-EI-014+ makes GraderRegistryPort itself introspectable.
_KNOWN_GRADERS = (
    GraderDescriptor("ClassificationAccuracyGrader", GraderType.DETERMINISTIC, EvaluationDimension.CLASSIFICATION_ACCURACY, "classification-accuracy-v1"),
    GraderDescriptor("ToolAllowlistGrader", GraderType.DETERMINISTIC, EvaluationDimension.TOOL_SELECTION, "tool-allowlist-v1"),
    GraderDescriptor("ExplanationQualityJudge", GraderType.LLM_JUDGE, EvaluationDimension.HANDOFF_COMPLETENESS, "explanation-quality-judge-placeholder-v0"),
)


class GraderCatalogService:
    def __init__(self, grader_registry: GraderRegistryPort) -> None:
        self._grader_registry = grader_registry  # kept for the port dependency; the catalog itself is the static list above

    def list_graders(self) -> tuple[GraderDescriptor, ...]:
        return _KNOWN_GRADERS
