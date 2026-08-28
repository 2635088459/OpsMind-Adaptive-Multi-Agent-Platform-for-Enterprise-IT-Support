"""13-package-and-class-design `infrastructure/langsmith/experiment_adapter.py`. Real
LangSmith Experiment mapping is SPEC-EI-013 (langsmith-experiment-linkage) scope.
"""

from __future__ import annotations


class NoOpLangSmithExperimentAdapter:
    def link_experiment(self, run_key: str, dataset_name: str, dataset_version: str) -> str | None:  # noqa: ARG002
        return None
