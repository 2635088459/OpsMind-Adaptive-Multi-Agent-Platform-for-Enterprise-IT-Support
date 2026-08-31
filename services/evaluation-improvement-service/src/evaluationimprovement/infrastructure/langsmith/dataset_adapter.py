"""13-package-and-class-design `infrastructure/langsmith/dataset_adapter.py`.
SPEC-EI-001 built NoOpLangSmithDatasetAdapter; SPEC-EI-013 adds
SdkLangSmithExperimentAdapter's own real LangSmith Dataset lookup/creation, used as
the `reference_dataset_id` a linked experiment (LangSmith's own "Project") points at.
`langsmith` is imported lazily and defensively (module import failure, not just a call
failure, must never crash service startup when Settings.langsmith_mode="noop" — the
only mode this adapter's own caller ever constructs by default) — mirrors
infrastructure.runtime.agent_runtime_client's own httpx usage being a hard dependency
only because that adapter is likewise never constructed unless its own mode is
selected.
"""

from __future__ import annotations

import logging

logger = logging.getLogger("evaluationimprovement.infrastructure.langsmith")


class NoOpLangSmithDatasetAdapter:
    def link_dataset(self, name: str, version: str) -> str | None:  # noqa: ARG002
        return None


class SdkLangSmithDatasetAdapter:
    """Get-or-create by name `{name}::{version}` — 07-data-model's own dataset
    identity (`(name, version)` unique key) mapped onto LangSmith's own single
    `dataset_name` string field, so re-linking the same OpsMind dataset version never
    creates a duplicate LangSmith dataset.
    """

    def __init__(self, client: object) -> None:
        self._client = client

    def link_dataset(self, name: str, version: str) -> str | None:
        dataset_name = f"{name}::{version}"
        try:
            if self._client.has_dataset(dataset_name=dataset_name):
                dataset = self._client.read_dataset(dataset_name=dataset_name)
            else:
                dataset = self._client.create_dataset(
                    dataset_name=dataset_name, description=f"OpsMind evaluation dataset {name} version {version}",
                )
            return str(dataset.id)
        except Exception:
            # 10-failure-handling §"LangSmith 故障": online/dataset-linkage failures
            # fail open — the caller (SdkLangSmithExperimentAdapter) treats a None
            # return as "unavailable," never raises it further up.
            logger.warning("LangSmith dataset link failed for %s", dataset_name, exc_info=True)
            return None
