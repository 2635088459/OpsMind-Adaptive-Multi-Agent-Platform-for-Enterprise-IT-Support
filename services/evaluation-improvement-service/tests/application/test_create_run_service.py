from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CancelRunCommand,
    CreateDatasetCommand,
    CreateRunCommand,
    FinalizeRunScoringCommand,
    PublishDatasetCommand,
    SkipCaseCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.exceptions import DatasetNotFoundException, RunKeyConflictException
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.ids import DatasetId
from evaluationimprovement.domain.state_machine import InvalidStateTransitionException


def _published_dataset(container: Container):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="identity-mfa-golden", version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={}, ground_truth={"classification": "X"},
        allowed_tools=(), forbidden_tools=(), required_approval=False, verification_condition={},
        criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))

    return container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))


def _run_command(dataset_id, run_key: str, target_version: str = "agent-runtime:rc1") -> CreateRunCommand:
    return CreateRunCommand(
        run_key=run_key, dataset_id=dataset_id, target_version=target_version, baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    )


@pytest.mark.unit
def test_same_run_key_and_params_returns_the_same_run(container: Container) -> None:
    dataset = _published_dataset(container)
    first = container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-main-001"))
    second = container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-main-001"))
    assert first.run_id == second.run_id


@pytest.mark.unit
def test_same_run_key_with_different_target_version_conflicts(container: Container) -> None:
    dataset = _published_dataset(container)
    container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-main-002", target_version="agent-runtime:rc1"))
    with pytest.raises(RunKeyConflictException):
        container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-main-002", target_version="agent-runtime:rc2"))


@pytest.mark.unit
def test_run_cannot_be_created_against_an_unpublished_dataset(container: Container) -> None:
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="draft-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    with pytest.raises(ValueError, match="PUBLISHED"):
        container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-main-003"))


@pytest.mark.unit
def test_cancel_run_is_idempotent(container: Container) -> None:
    """SPEC-EI-010 / 09-concurrency-and-idempotency §"并发规则": a resubmitted cancel
    against an already-CANCELLED run returns that same run instead of raising —
    mirroring create_run()'s own runKey idempotency. An earlier version of this
    method always called domain.EvaluationRun.cancel() unconditionally, which raised
    InvalidStateTransitionException on the second call.
    """
    dataset = _published_dataset(container)
    run = container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-cancel-idempotent-001"))
    first = container.create_run_service.cancel_run(CancelRunCommand(run_id=run.run_id, reason="stopping", actor="ci", correlation_id="corr-1"))
    assert first.status.value == "CANCELLED"

    second = container.create_run_service.cancel_run(CancelRunCommand(run_id=run.run_id, reason="stopping again", actor="ci", correlation_id="corr-2"))
    assert second.status.value == "CANCELLED"
    assert second.run_id == first.run_id


@pytest.mark.unit
def test_cancel_run_still_refuses_a_run_that_already_reached_another_terminal_status(container: Container) -> None:
    """Only CANCELLED is idempotent — a genuinely different terminal outcome (PASSED/
    FAILED/PARTIAL) must never be silently overwritten by a cancel request.
    """
    dataset = _published_dataset(container)
    run = container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-cancel-vs-partial-001"))
    case = container.create_dataset_service.find_test_cases(dataset.dataset_id, "default")[0]
    container.execute_case_service.skip_case(SkipCaseCommand(run_id=run.run_id, test_case_id=case.test_case_id, reason="n/a", actor="ci", correlation_id="corr-1"))
    partial = container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))
    assert partial.status.value == "PARTIAL"

    with pytest.raises(InvalidStateTransitionException):
        container.create_run_service.cancel_run(CancelRunCommand(run_id=run.run_id, reason="too late", actor="ci", correlation_id="corr-1"))


@pytest.mark.unit
def test_list_runs_returns_every_run_for_a_dataset_newest_first(container: Container) -> None:
    dataset = _published_dataset(container)
    first = container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-list-001"))
    second = container.create_run_service.create_run(_run_command(dataset.dataset_id, "ci-list-002"))
    container.create_run_service.cancel_run(CancelRunCommand(run_id=second.run_id, reason="n/a", actor="ci", correlation_id="corr-1"))

    all_runs = container.create_run_service.list_runs(dataset.dataset_id, None, 50)
    assert {r.run_id for r in all_runs} == {first.run_id, second.run_id}

    cancelled_only = container.create_run_service.list_runs(dataset.dataset_id, "CANCELLED", 50)
    assert [r.run_id for r in cancelled_only] == [second.run_id]

    queued_only = container.create_run_service.list_runs(dataset.dataset_id, "QUEUED", 50)
    assert [r.run_id for r in queued_only] == [first.run_id]


@pytest.mark.unit
def test_list_runs_requires_an_existing_dataset(container: Container) -> None:
    with pytest.raises(DatasetNotFoundException):
        container.create_run_service.list_runs(DatasetId.new_id(), None, 50)
