"""13-package-and-class-design `infrastructure/langsmith/client.py`. Satisfies
application.ports_out.LangSmithPort by delegating to dataset_adapter/
experiment_adapter — a genuinely no-op composition today (both delegate adapters are
no-ops), never claiming to reach a real LangSmith backend. 10-failure-handling
§"LangSmith 故障": "对离线 release gate：fail closed" — this adapter's `None` return
signals unavailability, and callers (e.g. CreateRunService) must not treat that as a
release-gate pass. Real LangSmith SDK wiring is SPEC-EI-013 scope.
"""

from __future__ import annotations

from evaluationimprovement.domain.ids import RunId
from evaluationimprovement.infrastructure.langsmith.experiment_adapter import NoOpLangSmithExperimentAdapter


class LangSmithClientAdapter:
    def __init__(self, experiment_adapter: NoOpLangSmithExperimentAdapter | None = None) -> None:
        self._experiment_adapter = experiment_adapter or NoOpLangSmithExperimentAdapter()

    def link_experiment(self, run_id: RunId, dataset_name: str, dataset_version: str) -> str | None:
        return self._experiment_adapter.link_experiment(str(run_id), dataset_name, dataset_version)
