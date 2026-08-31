"""SPEC-EI-022 (ci-evaluation-gate-harness): CiEvaluationGateService — the full
create_run -> case-runner-driven execution -> scoring -> finalize -> compare ->
evaluate-gate pipeline in one call, returning a `passed: bool` a CI job's exit code
reads directly.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CreateDatasetCommand,
    PublishDatasetCommand,
    RunCiGateCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality


def _publish_dataset(container: Container, name: str, *, case_kwargs: dict | None = None):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=name, version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    defaults = dict(
        case_key="k1", scenario="Duo enrollment expired", user_request_redacted="mfa broken", mock_system_state={},
        ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"}, allowed_tools=("reset_duo_enrollment",),
        forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.CRITICAL,
    )
    if case_kwargs:
        defaults.update(case_kwargs)
    case = TestCaseInput(**defaults)
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))
    return container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))


def _gate_command(dataset_id, run_key: str) -> RunCiGateCommand:
    return RunCiGateCommand(
        run_key=run_key, dataset_id=dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    )


@pytest.mark.unit
def test_a_correct_run_passes_the_gate(container: Container) -> None:
    dataset = _publish_dataset(container, "ci-gate-pass-dataset")
    outcome = container.ci_evaluation_gate_service.run_gate(_gate_command(dataset.dataset_id, "ci-gate-pass-001"))
    assert outcome.passed is True
    assert outcome.run_status == "PASSED"
    assert outcome.gate_decision == "PASSED"
    assert outcome.critical_failures == ()

    # The underlying run really did reach PASSED, not just an outcome-shaped claim.
    final_run = container.create_run_service.find_run(outcome.run_id)
    assert final_run.status.value == "PASSED"


@pytest.mark.unit
def test_a_wrong_classification_fails_the_gate(container: Container) -> None:
    dataset = _publish_dataset(container, "ci-gate-fail-dataset", case_kwargs={
        "mock_system_state": {"simulatedClassification": "WRONG_ANSWER"},
    })
    outcome = container.ci_evaluation_gate_service.run_gate(_gate_command(dataset.dataset_id, "ci-gate-fail-001"))
    assert outcome.passed is False
    assert outcome.run_status == "FAILED"
    assert outcome.gate_decision == "FAILED"
    assert len(outcome.critical_failures) == 1


@pytest.mark.unit
def test_resubmitting_an_already_passed_run_key_replays_the_same_gate_report(container: Container) -> None:
    """09-concurrency-and-idempotency: a resubmitted run_key must never re-drive an
    already-terminal run through score_case()/compare()/evaluate() a second time —
    each of those refuses a run that has already moved past the status they require.
    """
    dataset = _publish_dataset(container, "ci-gate-idempotent-dataset")
    command = _gate_command(dataset.dataset_id, "ci-gate-idempotent-001")
    first = container.ci_evaluation_gate_service.run_gate(command)
    second = container.ci_evaluation_gate_service.run_gate(command)
    assert first.run_id == second.run_id
    assert second.passed == first.passed is True
    assert second.gate_decision == first.gate_decision == "PASSED"
    assert second.critical_failures == first.critical_failures == ()


@pytest.mark.unit
def test_resubmitting_an_already_failed_run_key_replays_the_same_gate_report(container: Container) -> None:
    dataset = _publish_dataset(container, "ci-gate-idempotent-fail-dataset", case_kwargs={
        "mock_system_state": {"simulatedClassification": "WRONG_ANSWER"},
    })
    command = _gate_command(dataset.dataset_id, "ci-gate-idempotent-fail-001")
    first = container.ci_evaluation_gate_service.run_gate(command)
    second = container.ci_evaluation_gate_service.run_gate(command)
    assert first.run_id == second.run_id
    assert second.passed == first.passed is False
    assert second.gate_decision == first.gate_decision == "FAILED"
    assert second.critical_failures == first.critical_failures
