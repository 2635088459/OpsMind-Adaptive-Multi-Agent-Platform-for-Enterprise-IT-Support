from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    AdvanceCanaryCommand,
    ApproveCandidateCommand,
    CanaryStageInput,
    CompleteCanaryRollbackCommand,
    CreateDatasetCommand,
    CreateImprovementCandidateCommand,
    PauseCanaryCommand,
    PromoteCandidateCommand,
    PublishDatasetCommand,
    RecordCandidateBenchmarkCommand,
    RequestCandidateApprovalCommand,
    RequestCanaryRollbackCommand,
    RollbackPromotedCandidateCommand,
    RunCiGateCommand,
    StartCanaryCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CandidateType, Criticality, RiskLevel
from evaluationimprovement.domain.ids import IdempotencyKey, RunId


def _drive_run_to_passed(container: Container, run_key: str) -> RunId:
    """Mirrors test_create_improvement_candidate_service.py's own helper — the
    shortest path to a real EvaluationRun with a terminal PASSED release-gate
    decision, which SPEC-EI-025 requires `record_benchmark` to be bound to.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=f"canary-benchmark-{run_key}", version="1", domain="IDENTITY_ACCESS", scenario_tags=(),
        created_by="author-1", actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="Duo enrollment expired", user_request_redacted="mfa broken", mock_system_state={},
        ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"}, allowed_tools=("reset_duo_enrollment",),
        forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.CRITICAL,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))
    outcome = container.ci_evaluation_gate_service.run_gate(RunCiGateCommand(
        run_key=run_key, dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    assert outcome.passed is True
    return outcome.run_id


def _approved_candidate(container: Container):
    source_run_id = _drive_run_to_passed(container, f"canary-source-{id(container)}")
    candidate = container.create_improvement_candidate_service.create(CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=source_run_id, source_failure_cluster_id="c1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("idem-c1"),
    ))
    benchmark_run_id = _drive_run_to_passed(container, f"canary-benchmark-{candidate.candidate_id}")
    container.create_improvement_candidate_service.record_benchmark(RecordCandidateBenchmarkCommand(candidate_id=candidate.candidate_id, benchmark_run_id=benchmark_run_id, actor="author-1", correlation_id="corr-1"))
    container.create_improvement_candidate_service.request_approval(RequestCandidateApprovalCommand(candidate_id=candidate.candidate_id, actor="author-1", correlation_id="corr-1"))
    return container.create_improvement_candidate_service.approve(ApproveCandidateCommand(candidate_id=candidate.candidate_id, approved_by="approver-1", actor="approver-1", correlation_id="corr-1"))


@pytest.mark.unit
def test_start_canary_is_idempotent_under_the_same_key(container: Container) -> None:
    candidate = _approved_candidate(container)
    command = StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05, 50),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-1"),
    )
    first = container.manage_canary_service.start_canary(command)
    second = container.manage_canary_service.start_canary(command)
    assert first.canary_status.value == "ACTIVE"
    assert first.candidate_id == second.candidate_id
    assert first.canary_status == second.canary_status


@pytest.mark.unit
def test_canary_advances_through_stages_to_succeeded(container: Container) -> None:
    candidate = _approved_candidate(container)
    started = container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1",
        stages=(CanaryStageInput(5.0, 30, 0.05, 50), CanaryStageInput(25.0, 30, 0.05, 200)), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-2"),
    ))
    assert started.canary_status.value == "ACTIVE"

    expanding = container.manage_canary_service.advance(AdvanceCanaryCommand(candidate_id=candidate.candidate_id, actor="admin-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("advance-1")))
    assert expanding.canary_status.value == "EXPANDING"
    succeeded = container.manage_canary_service.advance(AdvanceCanaryCommand(candidate_id=candidate.candidate_id, actor="admin-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("advance-2")))
    assert succeeded.canary_status.value == "SUCCEEDED"


@pytest.mark.unit
def test_request_rollback_from_active_transitions_through_failed(container: Container) -> None:
    candidate = _approved_candidate(container)
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05, 50),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-3"),
    ))
    rollback_requested = container.manage_canary_service.request_rollback(RequestCanaryRollbackCommand(
        candidate_id=candidate.candidate_id, reason="error rate spike", actor="admin-1", correlation_id="corr-1",
        idempotency_key=IdempotencyKey("rollback-1"),
    ))
    assert rollback_requested.canary_status.value == "ROLLBACK_REQUESTED"


@pytest.mark.unit
def test_pause_and_resume_a_canary(container: Container) -> None:
    candidate = _approved_candidate(container)
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05, 50),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-pause"),
    ))
    paused = container.manage_canary_service.pause(PauseCanaryCommand(
        candidate_id=candidate.candidate_id, reason="investigating an anomaly", actor="admin-1", correlation_id="corr-1",
        idempotency_key=IdempotencyKey("pause-1"),
    ))
    assert paused.canary_status.value == "PAUSED"

    resumed = container.manage_canary_service.advance(AdvanceCanaryCommand(
        candidate_id=candidate.candidate_id, actor="admin-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("advance-resume-1"),
    ))
    assert resumed.canary_status.value == "ACTIVE"


@pytest.mark.unit
def test_request_rollback_then_complete_rollback_reaches_rolled_back(container: Container) -> None:
    candidate = _approved_candidate(container)
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05, 50),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-complete-rollback"),
    ))
    container.manage_canary_service.request_rollback(RequestCanaryRollbackCommand(
        candidate_id=candidate.candidate_id, reason="error rate spike", actor="admin-1", correlation_id="corr-1",
        idempotency_key=IdempotencyKey("rollback-complete-1"),
    ))
    completed = container.manage_canary_service.complete_rollback(CompleteCanaryRollbackCommand(
        candidate_id=candidate.candidate_id, actor="runtime-owner-1", correlation_id="corr-1",
    ))
    assert completed.canary_status.value == "ROLLED_BACK"
    assert completed.status.value == "ROLLED_BACK"


@pytest.mark.unit
def test_advance_to_succeeded_then_promote_reaches_promoted(container: Container) -> None:
    candidate = _approved_candidate(container)
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1",
        stages=(CanaryStageInput(5.0, 30, 0.05, 50), CanaryStageInput(25.0, 30, 0.05, 200)), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-promote"),
    ))
    container.manage_canary_service.advance(AdvanceCanaryCommand(
        candidate_id=candidate.candidate_id, actor="admin-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("advance-promote-1"),
    ))
    succeeded = container.manage_canary_service.advance(AdvanceCanaryCommand(
        candidate_id=candidate.candidate_id, actor="admin-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("advance-promote-2"),
    ))
    assert succeeded.canary_status.value == "SUCCEEDED"

    promoted = container.create_improvement_candidate_service.promote(PromoteCandidateCommand(
        candidate_id=candidate.candidate_id, promoted_version="agent-runtime:rc1", actor="admin-1", correlation_id="corr-1",
    ))
    assert promoted.status.value == "PROMOTED"
    assert promoted.promoted_version == "agent-runtime:rc1"


@pytest.mark.unit
def test_promote_before_canary_succeeded_is_rejected(container: Container) -> None:
    candidate = _approved_candidate(container)
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05, 50),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-promote-reject"),
    ))
    with pytest.raises(ValueError, match="cannot promote"):
        container.create_improvement_candidate_service.promote(PromoteCandidateCommand(
            candidate_id=candidate.candidate_id, promoted_version="agent-runtime:rc1", actor="admin-1", correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_rollback_promoted_candidate_reaches_rolled_back(container: Container) -> None:
    candidate = _approved_candidate(container)
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1",
        stages=(CanaryStageInput(5.0, 30, 0.05, 50), CanaryStageInput(25.0, 30, 0.05, 200)), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-rollback-promoted"),
    ))
    container.manage_canary_service.advance(AdvanceCanaryCommand(
        candidate_id=candidate.candidate_id, actor="admin-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("advance-rb-1"),
    ))
    container.manage_canary_service.advance(AdvanceCanaryCommand(
        candidate_id=candidate.candidate_id, actor="admin-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("advance-rb-2"),
    ))
    container.create_improvement_candidate_service.promote(PromoteCandidateCommand(
        candidate_id=candidate.candidate_id, promoted_version="agent-runtime:rc1", actor="admin-1", correlation_id="corr-1",
    ))

    rolled_back = container.manage_canary_service.rollback_promoted(RollbackPromotedCandidateCommand(
        candidate_id=candidate.candidate_id, reason="production incident", actor="admin-1", correlation_id="corr-1",
        idempotency_key=IdempotencyKey("rollback-promoted-1"),
    ))
    assert rolled_back.status.value == "ROLLED_BACK"
    assert rolled_back.canary_status.value == "ROLLED_BACK"


@pytest.mark.unit
def test_rollback_promoted_rejects_a_candidate_that_is_not_promoted(container: Container) -> None:
    candidate = _approved_candidate(container)
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05, 50),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-rb-reject"),
    ))
    with pytest.raises(ValueError, match="expected PROMOTED"):
        container.manage_canary_service.rollback_promoted(RollbackPromotedCandidateCommand(
            candidate_id=candidate.candidate_id, reason="premature rollback attempt", actor="admin-1", correlation_id="corr-1",
            idempotency_key=IdempotencyKey("rollback-promoted-reject-1"),
        ))
