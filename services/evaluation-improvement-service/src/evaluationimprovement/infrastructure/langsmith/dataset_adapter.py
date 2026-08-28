"""13-package-and-class-design `infrastructure/langsmith/dataset_adapter.py`. Real
LangSmith Dataset mapping is SPEC-EI-013 (langsmith-experiment-linkage) scope.
"""

from __future__ import annotations


class NoOpLangSmithDatasetAdapter:
    def link_dataset(self, name: str, version: str) -> str | None:  # noqa: ARG002
        return None
