"""SPEC-EI-001's own walking-skeleton proof: create_dataset -> publish_dataset ->
create_run -> execute_case -> score_case -> finalize_scoring -> compare_regression ->
evaluate_release_gate, end to end against the real domain/application code with only
SPEC-EI-001's own in-memory adapters underneath. Mirrors 04-use-cases UC-EI-002/
UC-EI-003 exactly.
"""

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
    SkipCaseCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.exceptions import (
    CaseExecutionNotCompletedException,
    IncompleteRunException,
    StaleResultException,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CaseExecutionStatus, Criticality


def _publish_dataset_with_case(container: Container, *, ground_truth_matches_fake_adapter: bool = True):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="identity-mfa-golden", version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=("mfa",),
        created_by="author-1", actor="author-1", correlation_id="corr-1",
    ))
    # infrastructure.runtime.agent_runtime_client.FakeAgentRuntimeEvaluationAdapter
    # echoes groundTruth by default (a "correct agent"); simulatedClassification
    # forces it to answer wrong instead, without touching groundTruth itself (the
    # grader must still compare against the real ground truth).
    mock_system_state = {"duoStatus": "EXPIRED"} if ground_truth_matches_fake_adapter else {"duoStatus": "EXPIRED", "simulatedClassification": "WRONG_ANSWER"}
    case = TestCaseInput(
        case_key="duo-enrollment-expired", scenario="Duo enrollment expired", user_request_redacted="mfa broken",
        mock_system_state=mock_system_state, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=("reset_duo_enrollment",), forbidden_tools=("disable_mfa",), required_approval=False,
        verification_condition={"duoStatus": "ACTIVE"}, criticality=Criticality.CRITICAL,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))
    return published, added[0]


def _create_and_run_to_comparing(container: Container, run_key: str, *, ground_truth_matches: bool = True):
    dataset, test_case = _publish_dataset_with_case(container, ground_truth_matches_fake_adapter=ground_truth_matches)
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key=run_key, dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.execute_case(ExecuteCaseCommand(
        run_id=run.run_id, test_case_id=test_case.test_case_id, attempt=1, actor="ci", correlation_id="corr-1",
    ))
    container.score_run_service.score_case(ScoreCaseCommand(
        run_id=run.run_id, test_case_id=test_case.test_case_id, run_generation=1, actor="ci", correlation_id="corr-1",
    ))
    comparing_run = container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(
        run_id=run.run_id, actor="ci", correlation_id="corr-1",
    ))
    assert comparing_run.status.value == "COMPARING"
    return run, test_case


@pytest.mark.unit
def test_full_pipeline_passes_when_agent_gets_everything_right(container: Container) -> None:
    run, _ = _create_and_run_to_comparing(container, "ci-main-pass-001")
    report = container.compare_regression_service.compare(CompareRegressionCommand(
        run_id=run.run_id, baseline_run_id=None, actor="ci", correlation_id="corr-1",
    ))
    assert report.overall_decision.value == "PASSED"

    final_report = container.evaluate_release_gate_service.evaluate(EvaluateReleaseGateCommand(
        run_id=run.run_id, gate_policy="mvp-release-gate-v1", actor="ci", correlation_id="corr-1",
    ))
    assert final_report.overall_decision.value == "PASSED"
    final_run = container.create_run_service.find_run(run.run_id)
    assert final_run.status.value == "PASSED"


@pytest.mark.unit
def test_pipeline_fails_gate_when_a_critical_case_fails(container: Container) -> None:
    """02-business-invariants INV-EI-008: a failed CRITICAL case forces the gate to
    fail even though nothing else about the run is wrong.
    """
    run, _ = _create_and_run_to_comparing(container, "ci-main-fail-001", ground_truth_matches=False)
    report = container.compare_regression_service.compare(CompareRegressionCommand(
        run_id=run.run_id, baseline_run_id=None, actor="ci", correlation_id="corr-1",
    ))
    assert report.overall_decision.value == "FAILED"
    assert len(report.critical_failures) == 1

    final_report = container.evaluate_release_gate_service.evaluate(EvaluateReleaseGateCommand(
        run_id=run.run_id, gate_policy="mvp-release-gate-v1", actor="ci", correlation_id="corr-1",
    ))
    assert final_report.overall_decision.value == "FAILED"
    final_run = container.create_run_service.find_run(run.run_id)
    assert final_run.status.value == "FAILED"


@pytest.mark.unit
def test_finalize_scoring_rejects_a_run_still_in_queued(container: Container) -> None:
    dataset, _test_case = _publish_dataset_with_case(container)
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="ci-incomplete-001", dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1",
        baseline_version=None, grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1",
        triggered_by="ci", actor="ci", correlation_id="corr-1",
    ))
    with pytest.raises(ValueError, match="expected SCORING"):
        container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))


@pytest.mark.unit
def test_finalize_scoring_rejects_a_run_with_an_unscored_case(container: Container) -> None:
    """08-transaction-and-outbox §"Run 完成事务": every expected case must have a score
    before a run leaves SCORING.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="two-case-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    first_input = TestCaseInput(
        case_key="duo-enrollment-expired", scenario="s", user_request_redacted="", mock_system_state={},
        ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"}, allowed_tools=(), forbidden_tools=(),
        required_approval=False, verification_condition={}, criticality=Criticality.STANDARD,
    )
    second_case = TestCaseInput(
        case_key="okta-session-invalid", scenario="Okta session invalid", user_request_redacted="cannot log in",
        mock_system_state={}, ground_truth={"classification": "OKTA_SESSION_INVALID"}, allowed_tools=(),
        forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.STANDARD,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(first_input, second_case), actor="author-1", correlation_id="corr-1",
    ))
    first_case = added[0]
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    dataset = container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="ci-incomplete-002", dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1",
        baseline_version=None, grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1",
        triggered_by="ci", actor="ci", correlation_id="corr-1",
    ))
    # Only the first case is executed and scored; the second case (added[1]) is left
    # unscored.
    container.execute_case_service.execute_case(ExecuteCaseCommand(
        run_id=run.run_id, test_case_id=first_case.test_case_id, attempt=1, actor="ci", correlation_id="corr-1",
    ))
    container.score_run_service.score_case(ScoreCaseCommand(
        run_id=run.run_id, test_case_id=first_case.test_case_id, run_generation=1, actor="ci", correlation_id="corr-1",
    ))
    assert len(added) == 2
    with pytest.raises(IncompleteRunException):
        container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))


@pytest.mark.unit
def test_stale_generation_result_is_rejected(container: Container) -> None:
    dataset, test_case = _publish_dataset_with_case(container)
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="ci-stale-001", dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.execute_case(ExecuteCaseCommand(
        run_id=run.run_id, test_case_id=test_case.test_case_id, attempt=1, actor="ci", correlation_id="corr-1",
    ))
    with pytest.raises(StaleResultException):
        container.score_run_service.score_case(ScoreCaseCommand(
            run_id=run.run_id, test_case_id=test_case.test_case_id, run_generation=999, actor="ci", correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_execute_case_records_a_failed_result_instead_of_propagating(container: Container) -> None:
    """SPEC-EI-009: an earlier version of ExecuteCaseService let a runner exception
    propagate unhandled, leaving the case permanently unaccounted-for and blocking
    the run in SCORING forever. It must instead be recorded as a FAILED
    CaseExecutionResult.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="runner-error-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case_input = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={"simulateRunnerError": "agent runtime timed out"},
        ground_truth={"classification": "X"}, allowed_tools=(), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case_input,), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="ci-runner-error-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1",
        baseline_version=None, grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1",
        triggered_by="ci", actor="ci", correlation_id="corr-1",
    ))

    # Does not raise — the runner's own RuntimeError is caught inside execute_case().
    container.execute_case_service.execute_case(ExecuteCaseCommand(
        run_id=run.run_id, test_case_id=added[0].test_case_id, attempt=1, actor="ci", correlation_id="corr-1",
    ))
    result = container.case_execution_result_repository.find(run.run_id, added[0].test_case_id)
    assert result is not None
    assert result.status == CaseExecutionStatus.FAILED
    assert result.failure_reason == "agent runtime timed out"

    # A FAILED result is never eligible for scoring — grading its empty fields would
    # be meaningless.
    with pytest.raises(CaseExecutionNotCompletedException):
        container.score_run_service.score_case(ScoreCaseCommand(
            run_id=run.run_id, test_case_id=added[0].test_case_id, run_generation=1, actor="ci", correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_finalize_scoring_accounts_for_failed_and_skipped_cases_and_marks_partial(container: Container) -> None:
    """SPEC-EI-009 / 10-failure-handling §"Partial Run": a run with a mix of scored,
    FAILED, and SKIPPED cases can still leave SCORING — as PARTIAL, never COMPARING,
    since the release gate must never be evaluated against an incomplete result set.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="partial-run-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    scored_case = TestCaseInput(
        case_key="scored-case", scenario="s", user_request_redacted="", mock_system_state={},
        ground_truth={"classification": "X"}, allowed_tools=(), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    failed_case = TestCaseInput(
        case_key="failed-case", scenario="s", user_request_redacted="", mock_system_state={"simulateRunnerError": "boom"},
        ground_truth={"classification": "X"}, allowed_tools=(), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    skipped_case = TestCaseInput(
        case_key="skipped-case", scenario="s", user_request_redacted="", mock_system_state={},
        ground_truth={"classification": "X"}, allowed_tools=(), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(scored_case, failed_case, skipped_case), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="ci-partial-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1",
        baseline_version=None, grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1",
        triggered_by="ci", actor="ci", correlation_id="corr-1",
    ))

    # Every case's own execute/skip happens first, while the run is still RUNNING —
    # the first score_case() call below is what drives RUNNING -> SCORING, after
    # which execute_case()/skip_case() can no longer be called for this run.
    container.execute_case_service.execute_case(ExecuteCaseCommand(
        run_id=run.run_id, test_case_id=added[0].test_case_id, attempt=1, actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.execute_case(ExecuteCaseCommand(
        run_id=run.run_id, test_case_id=added[1].test_case_id, attempt=1, actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.skip_case(SkipCaseCommand(
        run_id=run.run_id, test_case_id=added[2].test_case_id, reason="known flaky case", actor="ci", correlation_id="corr-1",
    ))
    container.score_run_service.score_case(ScoreCaseCommand(
        run_id=run.run_id, test_case_id=added[0].test_case_id, run_generation=1, actor="ci", correlation_id="corr-1",
    ))

    finalized = container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))
    assert finalized.status.value == "PARTIAL"

    # A PARTIAL run never reaches COMPARING, so compare()/evaluate() must both refuse it.
    with pytest.raises(ValueError, match="expected COMPARING"):
        container.compare_regression_service.compare(CompareRegressionCommand(
            run_id=run.run_id, baseline_run_id=None, actor="ci", correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_finalize_scoring_reaches_partial_directly_from_running_when_nothing_was_ever_scored(container: Container) -> None:
    """SPEC-EI-009: score_case() is what normally drives RUNNING -> SCORING — a run
    whose only case is skipped never calls it at all, and must still be finalizable
    (via the domain's own pre-existing RUNNING -> PARTIAL transition) rather than
    permanently stuck in RUNNING.
    """
    dataset, test_case = _publish_dataset_with_case(container)
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="ci-all-skipped-001", dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1",
        baseline_version=None, grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1",
        triggered_by="ci", actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.skip_case(SkipCaseCommand(
        run_id=run.run_id, test_case_id=test_case.test_case_id, reason="known flaky case", actor="ci", correlation_id="corr-1",
    ))
    assert container.create_run_service.find_run(run.run_id).status.value == "RUNNING"

    finalized = container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))
    assert finalized.status.value == "PARTIAL"


@pytest.mark.unit
def test_skip_case_requires_an_existing_run_and_test_case(container: Container) -> None:
    from evaluationimprovement.application.exceptions import RunNotFoundException, TestCaseNotFoundException
    from evaluationimprovement.domain.ids import RunId, TestCaseId

    with pytest.raises(RunNotFoundException):
        container.execute_case_service.skip_case(SkipCaseCommand(
            run_id=RunId.new_id(), test_case_id=TestCaseId.new_id(), reason="n/a", actor="ci", correlation_id="corr-1",
        ))

    dataset, test_case = _publish_dataset_with_case(container)
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="ci-skip-unknown-case-001", dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1",
        baseline_version=None, grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1",
        triggered_by="ci", actor="ci", correlation_id="corr-1",
    ))
    with pytest.raises(TestCaseNotFoundException):
        container.execute_case_service.skip_case(SkipCaseCommand(
            run_id=run.run_id, test_case_id=TestCaseId.new_id(), reason="n/a", actor="ci", correlation_id="corr-1",
        ))
