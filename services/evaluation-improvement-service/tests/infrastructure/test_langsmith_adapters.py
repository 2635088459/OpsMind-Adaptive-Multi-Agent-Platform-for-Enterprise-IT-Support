"""SPEC-EI-013 (langsmith-experiment-linkage). SdkLangSmithDatasetAdapter/
SdkLangSmithExperimentAdapter both type their own `client` param as `object` (never
`langsmith.Client`) precisely so they are testable against a duck-typed fake here
without the real `langsmith` package installed — see dataset_adapter's own module
docstring.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.records import LangSmithCaseResult
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
        self.created_runs: list[dict] = []
        self.created_feedback: list[dict] = []
        self.flushed = False

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

    # --- push_run_results collaborators ---
    def read_project(self, project_id: str) -> _FakeRecord:
        rec = _FakeRecord(project_id)
        rec.name = f"project-{project_id}"
        return rec

    def create_run(self, **kwargs: object) -> None:
        self.created_runs.append(kwargs)

    def create_feedback(self, **kwargs: object) -> None:
        self.created_feedback.append(kwargs)

    def flush(self) -> None:
        self.flushed = True


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


def _case(case_key: str, tokens: int) -> LangSmithCaseResult:
    prompt = int(tokens * 0.7)
    return LangSmithCaseResult(
        case_key=case_key, scenario=f"scenario {case_key}", classification="RESOLVED", final_state="COMPLETED",
        tool_calls=("send_password_reset",), total_tokens=tokens, prompt_tokens=prompt, completion_tokens=tokens - prompt,
        latency_ms=420,
        dimension_scores=(("CLASSIFICATION_ACCURACY", 0.94, True), ("TOOL_SELECTION", 0.86, False)),
    )


@pytest.mark.unit
def test_push_run_results_creates_a_run_and_feedback_per_case() -> None:
    client = _FakeLangSmithClient()
    adapter = SdkLangSmithExperimentAdapter(client, SdkLangSmithDatasetAdapter(client))

    pushed = adapter.push_run_results("proj-1", "run-key-1", [_case("case-1", 100), _case("case-2", 250)])

    assert pushed == 2
    assert [r["name"] for r in client.created_runs] == ["case-1", "case-2"]
    assert client.created_runs[0]["run_type"] == "llm"
    assert client.created_runs[0]["total_tokens"] == 100
    assert client.created_runs[1]["total_tokens"] == 250
    # the real prompt/completion split is forwarded, not just a single total
    assert client.created_runs[0]["prompt_tokens"] == 70
    assert client.created_runs[0]["completion_tokens"] == 30
    assert client.created_runs[0]["outputs"]["usage_metadata"] == {
        "input_tokens": 70, "output_tokens": 30, "total_tokens": 100,
    }
    # one feedback per graded dimension per case
    assert len(client.created_feedback) == 4
    assert {f["key"] for f in client.created_feedback} == {"CLASSIFICATION_ACCURACY", "TOOL_SELECTION"}
    assert client.created_feedback[0]["session_id"] == "proj-1"
    assert client.flushed is True


@pytest.mark.unit
def test_push_run_results_fails_open_when_the_project_is_unreadable() -> None:
    class _NoProject(_FakeLangSmithClient):
        def read_project(self, project_id: str):  # noqa: ARG002
            raise RuntimeError("gone")

    client = _NoProject()
    adapter = SdkLangSmithExperimentAdapter(client, SdkLangSmithDatasetAdapter(client))
    assert adapter.push_run_results("proj-1", "run-key-1", [_case("case-1", 100)]) == 0
    assert client.created_runs == []


@pytest.mark.unit
def test_push_run_results_skips_only_the_failing_case() -> None:
    class _FlakyRuns(_FakeLangSmithClient):
        def create_run(self, **kwargs: object) -> None:
            if kwargs.get("name") == "case-2":
                raise RuntimeError("transient")
            super().create_run(**kwargs)

    client = _FlakyRuns()
    adapter = SdkLangSmithExperimentAdapter(client, SdkLangSmithDatasetAdapter(client))
    pushed = adapter.push_run_results("proj-1", "run-key-1", [_case("case-1", 100), _case("case-2", 100), _case("case-3", 100)])
    assert pushed == 2
    assert [r["name"] for r in client.created_runs] == ["case-1", "case-3"]


@pytest.mark.unit
def test_noop_and_client_adapter_push_return_zero() -> None:
    assert NoOpLangSmithExperimentAdapter().push_run_results("proj-1", "run-key-1", [_case("case-1", 100)]) == 0
    assert LangSmithClientAdapter().push_run_results("proj-1", "run-key-1", [_case("case-1", 100)]) == 0


@pytest.mark.unit
def test_client_adapter_is_enabled_reflects_construction() -> None:
    assert LangSmithClientAdapter().is_enabled() is False
    assert LangSmithClientAdapter(enabled=False).is_enabled() is False
    assert LangSmithClientAdapter(experiment_adapter=NoOpLangSmithExperimentAdapter(), enabled=True).is_enabled() is True
