"""13-package-and-class-design `infrastructure/langsmith/experiment_adapter.py`.
SPEC-EI-001 built NoOpLangSmithExperimentAdapter; SPEC-EI-013 adds
SdkLangSmithExperimentAdapter, the real LangSmith Experiment (Project) mapping —
see dataset_adapter's own module docstring for why `client` stays untyped here too.
"""

from __future__ import annotations

import logging

from evaluationimprovement.infrastructure.langsmith.dataset_adapter import SdkLangSmithDatasetAdapter

logger = logging.getLogger("evaluationimprovement.infrastructure.langsmith")


class NoOpLangSmithExperimentAdapter:
    def link_experiment(self, run_key: str, dataset_name: str, dataset_version: str) -> str | None:  # noqa: ARG002
        return None


class SdkLangSmithExperimentAdapter:
    """04-use-cases UC-EI-002 step 4: "07 收集 LangSmith experiment." LangSmith's own
    closest concept to a named "experiment run" is a Project bound to a reference
    dataset (`client.create_project(reference_dataset_id=...)`) — no real evaluate()
    call happens here (this domain never sends prompts through LangSmith's own runner;
    Agent Runtime's own execution is the thing under test), so linkage is exactly
    that: create/reuse the Project, return its id as the opaque experiment reference.
    """

    def __init__(self, client: object, dataset_adapter: SdkLangSmithDatasetAdapter) -> None:
        self._client = client
        self._dataset_adapter = dataset_adapter

    def link_experiment(self, run_key: str, dataset_name: str, dataset_version: str) -> str | None:
        reference_dataset_id = self._dataset_adapter.link_dataset(dataset_name, dataset_version)
        if reference_dataset_id is None:
            return None
        project_name = f"opsmind-eval-{dataset_name}-{dataset_version}-{run_key}"
        try:
            project = self._client.create_project(project_name=project_name, reference_dataset_id=reference_dataset_id)
            return str(project.id)
        except Exception:
            logger.warning("LangSmith experiment link failed for %s", project_name, exc_info=True)
            return None
