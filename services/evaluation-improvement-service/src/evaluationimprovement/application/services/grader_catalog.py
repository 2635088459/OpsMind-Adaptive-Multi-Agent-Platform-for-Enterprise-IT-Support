"""Not among 13-package-and-class-design's ten named services — added the same way
audit_query.py was: 05-api-contracts §"管理 API" `GET /evaluation/graders` needs a real
read surface over infrastructure.graders.registry.GraderRegistry's own catalog,
reached only through GraderRegistryPort (application must not depend on
infrastructure).

SPEC-EI-001 kept a hand-maintained static tuple here, in sync with
infrastructure.graders.registry.GraderRegistry's own SPEC-EI-001 scope only by
convention — that module's own docstring named this exact gap: "until SPEC-EI-014+
makes GraderRegistryPort itself introspectable." SPEC-EI-014 closes it:
GraderRegistryPort.list_registered() is now the source of truth, so this service can
never drift from what is actually registered again — including which LLM_JUDGE
adapter is actually active (placeholder vs. the real judge SPEC-EI-016 added).
"""

from __future__ import annotations

from evaluationimprovement.application.ports_out import GraderRegistryPort
from evaluationimprovement.application.views import GraderDescriptor


class GraderCatalogService:
    def __init__(self, grader_registry: GraderRegistryPort) -> None:
        self._grader_registry = grader_registry

    def list_graders(self) -> tuple[GraderDescriptor, ...]:
        return tuple(
            GraderDescriptor(name, grader_type, dimension, version)
            for name, grader_type, dimension, version in self._grader_registry.list_registered()
        )
