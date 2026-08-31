"""13-package-and-class-design `infrastructure/langsmith/client.py`. Satisfies
application.ports_out.LangSmithPort by delegating to experiment_adapter (which itself
delegates dataset lookup to dataset_adapter). SPEC-EI-001 built the no-op-only shape;
SPEC-EI-013 adds `enabled` — see `is_enabled()`'s own docstring and
LangSmithPort.is_enabled()'s own docstring for why EvaluateReleaseGateService needs to
tell "never configured" apart from "configured but this call failed" (10-failure-
handling §"LangSmith 故障": "对离线 release gate：fail closed" applies only to the latter).
"""

from __future__ import annotations

from evaluationimprovement.domain.ids import RunId
from evaluationimprovement.infrastructure.langsmith.experiment_adapter import NoOpLangSmithExperimentAdapter


class LangSmithClientAdapter:
    def __init__(self, experiment_adapter: NoOpLangSmithExperimentAdapter | None = None, enabled: bool = False) -> None:
        self._experiment_adapter = experiment_adapter or NoOpLangSmithExperimentAdapter()
        self._enabled = enabled

    def link_experiment(self, run_id: RunId, dataset_name: str, dataset_version: str) -> str | None:
        return self._experiment_adapter.link_experiment(str(run_id), dataset_name, dataset_version)

    def is_enabled(self) -> bool:
        """False for the default no-op construction (this deployment never attempts a
        real LangSmith call — not a failure). True only when container.py actually
        wired infrastructure.langsmith.experiment_adapter.SdkLangSmithExperimentAdapter
        underneath (Settings.langsmith_mode="sdk" and the `langsmith` SDK imported
        successfully) — see container.py's own `_build_langsmith_adapter()`.
        """
        return self._enabled
