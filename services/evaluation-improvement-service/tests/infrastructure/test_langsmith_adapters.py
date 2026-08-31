"""SPEC-EI-013 (langsmith-experiment-linkage). SdkLangSmithDatasetAdapter/
SdkLangSmithExperimentAdapter both type their own `client` param as `object` (never
`langsmith.Client`) precisely so they are testable against a duck-typed fake here
without the real `langsmith` package installed — see dataset_adapter's own module
docstring.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.infrastructure.langsmith.client import LangSmithClientAdapter
from evaluationimprovement.infrastructure.langsmith.dataset_adapter import SdkLangSmithDatasetAdapter
from evaluationimprovement.infrastructure.langsmith.experiment_adapter import (
    NoOpLangSmithExperimentAdapter,
    SdkLangSmithExperimentAdapter,
)


class _FakeRecord:
    def __init__(self, id: str) -> None:  # noqa: A002
        self.id = id


class _FakeLangSmithClient:
    def __init__(self, existing_dataset_id: str | None = None, fail_create_dataset: bool = False, fail_create_project: bool = False) -> None:
        self._existing_dataset_id = existing_dataset_id
        self._fail_create_dataset = fail_create_dataset
        self._fail_create_project = fail_create_project
        self.created_datasets: list[str] = []
        self.created_projects: list[tuple[str, str]] = []

    def has_dataset(self, dataset_name: str) -> bool:  # noqa: ARG002
        return self._existing_dataset_id is not None

    def read_dataset(self, dataset_name: str) -> _FakeRecord:  # noqa: ARG002
        assert self._existing_dataset_id is not None
        return _FakeRecord(self._existing_dataset_id)

    def create_dataset(self, dataset_name: str, description: str | None = None) -> _FakeRecord:  # noqa: ARG002
        if self._fail_create_dataset:
            raise RuntimeError("langsmith unreachable")
        self.created_datasets.append(dataset_name)
        return _FakeRecord("new-dataset-id")

    def create_project(self, project_name: str, reference_dataset_id: str) -> _FakeRecord:
        if self._fail_create_project:
            raise RuntimeError("langsmith unreachable")
        self.created_projects.append((project_name, reference_dataset_id))
        return _FakeRecord("new-project-id")


@pytest.mark.unit
def test_dataset_adapter_creates_when_missing() -> None:
    client = _FakeLangSmithClient()
    adapter = SdkLangSmithDatasetAdapter(client)
    ref = adapter.link_dataset("identity-mfa-golden", "2026.08.1")
    assert ref == "new-dataset-id"
    assert client.created_datasets == ["identity-mfa-golden::2026.08.1"]


@pytest.mark.unit
def test_dataset_adapter_reuses_an_existing_dataset() -> None:
    client = _FakeLangSmithClient(existing_dataset_id="existing-dataset-id")
    adapter = SdkLangSmithDatasetAdapter(client)
    ref = adapter.link_dataset("identity-mfa-golden", "2026.08.1")
    assert ref == "existing-dataset-id"
    assert client.created_datasets == []


@pytest.mark.unit
def test_dataset_adapter_fails_open_on_error() -> None:
    client = _FakeLangSmithClient(fail_create_dataset=True)
    adapter = SdkLangSmithDatasetAdapter(client)
    assert adapter.link_dataset("identity-mfa-golden", "2026.08.1") is None


@pytest.mark.unit
def test_experiment_adapter_links_via_a_reference_dataset() -> None:
    client = _FakeLangSmithClient()
    adapter = SdkLangSmithExperimentAdapter(client, SdkLangSmithDatasetAdapter(client))
    ref = adapter.link_experiment("run-key-1", "identity-mfa-golden", "2026.08.1")
    assert ref == "new-project-id"
    assert client.created_projects == [("opsmind-eval-identity-mfa-golden-2026.08.1-run-key-1", "new-dataset-id")]


@pytest.mark.unit
def test_experiment_adapter_returns_none_when_the_dataset_link_fails() -> None:
    client = _FakeLangSmithClient(fail_create_dataset=True)
    adapter = SdkLangSmithExperimentAdapter(client, SdkLangSmithDatasetAdapter(client))
    assert adapter.link_experiment("run-key-1", "identity-mfa-golden", "2026.08.1") is None
    assert client.created_projects == []


@pytest.mark.unit
def test_experiment_adapter_fails_open_on_project_creation_error() -> None:
    client = _FakeLangSmithClient(fail_create_project=True)
    adapter = SdkLangSmithExperimentAdapter(client, SdkLangSmithDatasetAdapter(client))
    assert adapter.link_experiment("run-key-1", "identity-mfa-golden", "2026.08.1") is None


@pytest.mark.unit
def test_client_adapter_is_enabled_reflects_construction() -> None:
    assert LangSmithClientAdapter().is_enabled() is False
    assert LangSmithClientAdapter(enabled=False).is_enabled() is False
    assert LangSmithClientAdapter(experiment_adapter=NoOpLangSmithExperimentAdapter(), enabled=True).is_enabled() is True
