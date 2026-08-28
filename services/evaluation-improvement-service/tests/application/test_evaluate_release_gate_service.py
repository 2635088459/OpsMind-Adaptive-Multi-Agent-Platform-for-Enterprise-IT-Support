from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CompareRegressionCommand,
    CreateDatasetCommand,
    CreateRunCommand,
    EvaluateReleaseGateCommand,
    ExecuteCaseCommand,
    FinalizeRunScoringCommand,
    PublishDatasetCommand,
    ScoreCaseCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.exceptions import GatePolicyNotFoundException
from evaluationimprovement.application.records import GatePolicyConfig
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality


def _run_to_comparing(container: Container, run_key: str):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="identity-mfa-golden", version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={}, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=(), forbidden_tools=(), required_approval=False, verification_condition={},
        criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))

    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key=run_key, dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    test_case = container.test_case_repository.find_by_dataset(published.dataset_id)[0]
    container.execute_case_service.execute_case(ExecuteCaseCommand(run_id=run.run_id, test_case_id=test_case.test_case_id, attempt=1, actor="ci", correlation_id="corr-1"))
    container.score_run_service.score_case(ScoreCaseCommand(run_id=run.run_id, test_case_id=test_case.test_case_id, run_generation=1, actor="ci", correlation_id="corr-1"))
    container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))
    container.compare_regression_service.compare(CompareRegressionCommand(run_id=run.run_id, baseline_run_id=None, actor="ci", correlation_id="corr-1"))
    return run


@pytest.mark.unit
def test_unknown_gate_policy_raises(container: Container) -> None:
    run = _run_to_comparing(container, "gate-unknown-001")
    with pytest.raises(GatePolicyNotFoundException):
        container.evaluate_release_gate_service.evaluate(EvaluateReleaseGateCommand(run_id=run.run_id, gate_policy="does-not-exist", actor="ci", correlation_id="corr-1"))


@pytest.mark.unit
def test_an_unreachable_dimension_threshold_fails_the_gate_even_when_safety_gates_pass(container: Container) -> None:
    """The zero-tolerance/critical-case gate can pass while a stricter, named gate
    policy's own dimension threshold still fails the release — the two layers are
    independent (EvaluateReleaseGateService only ever tightens, never loosens, the
    report's own decision).
    """
    container.gate_policy_repository.save(GatePolicyConfig(
        gate_policy="impossible-gate", dimension_thresholds={"CLASSIFICATION_ACCURACY": 2.0},
    ))
    run = _run_to_comparing(container, "gate-impossible-001")
    container.evaluate_release_gate_service.evaluate(EvaluateReleaseGateCommand(run_id=run.run_id, gate_policy="impossible-gate", actor="ci", correlation_id="corr-1"))
    final_run = container.create_run_service.find_run(run.run_id)
    assert final_run.status.value == "FAILED"


@pytest.mark.unit
def test_gate_policy_cannot_loosen_zero_tolerance_counters(container: Container) -> None:
    with pytest.raises(ValueError, match="zero-tolerance"):
        container.evaluate_release_gate_service.upsert_gate_policy(
            GatePolicyConfig(gate_policy="loose-gate", dimension_thresholds={}, max_policy_violations=1), actor="admin-1",
        )
