"""SPEC-EI-022 (ci-evaluation-gate-harness): the actual `evaluation-ci-gate` CLI
entry point — argv parsing and the exit-code contract a CI pipeline depends on.
`ci_gate.main()` returns an int rather than calling sys.exit() itself specifically so
it stays directly testable here without subprocessing.
"""

from __future__ import annotations

import json

import pytest

from evaluationimprovement import ci_gate
from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CreateDatasetCommand,
    PublishDatasetCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.container import get_container
from evaluationimprovement.domain.enums import Criticality


@pytest.fixture(autouse=True)
def _reset_container_cache():
    """ci_gate.main() builds its own Container() (get_settings()-backed, same
    process-wide in-memory persistence tests/conftest.py already forces) — cleared
    before/after so this test's dataset doesn't leak into get_container()'s own
    lru_cache singleton other tests might rely on.
    """
    get_container.cache_clear()
    yield
    get_container.cache_clear()


def _publish_dataset_via_the_shared_container() -> tuple[str, str]:
    """ci_gate.main() reaches the process-wide get_container() singleton — this seeds
    a dataset through that exact same accessor, mirroring how an operator's own setup
    step (a real, separate `evaluation-ci-gate` invocation, or the REST API) and this
    CLI process would genuinely share one running service's data in production.
    """
    container = get_container()
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="ci-gate-cli-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={},
        ground_truth={"classification": "X"}, allowed_tools=(), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    return str(published.dataset_id), container.settings.evaluation_persistence


@pytest.mark.unit
def test_cli_exits_zero_and_prints_json_on_a_passing_gate(capsys: pytest.CaptureFixture[str]) -> None:
    dataset_id, _ = _publish_dataset_via_the_shared_container()

    exit_code = ci_gate.main([
        "--run-key", "ci-gate-cli-001", "--dataset-id", dataset_id, "--target-version", "agent-runtime:rc1",
        "--grader-bundle-version", "v1", "--policy-version", "v1", "--gate-policy", "mvp-release-gate-v1", "--json",
    ])
    assert exit_code == 0

    out = json.loads(capsys.readouterr().out.strip())
    assert out["passed"] is True
    assert out["gateDecision"] == "PASSED"


@pytest.mark.unit
def test_cli_exits_nonzero_on_an_unknown_dataset(capsys: pytest.CaptureFixture[str]) -> None:
    import uuid

    exit_code = ci_gate.main([
        "--run-key", "ci-gate-cli-002", "--dataset-id", str(uuid.uuid4()), "--target-version", "agent-runtime:rc1",
        "--grader-bundle-version", "v1", "--policy-version", "v1", "--gate-policy", "mvp-release-gate-v1",
    ])
    assert exit_code != 0
