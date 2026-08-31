"""SPEC-EI-032 (policy-approval-release-approval-contract): ConsumeApprovalDecisionEventService
— closes the request/consume loop SPEC-EI-026 deferred: an `approval.granted.v1`/
`approval.denied.v1` event for one of 07's own requests drives approve()/reject()
automatically.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    ConsumeApprovalDeniedCommand,
    ConsumeApprovalGrantedCommand,
    CreateDatasetCommand,
    CreateImprovementCandidateCommand,
    PublishDatasetCommand,
    RecordCandidateBenchmarkCommand,
    RejectCandidateCommand,
    RequestCandidateApprovalCommand,
    RunCiGateCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.exceptions import PoisonApprovalDecisionEventException
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CandidateType, Criticality, RiskLevel
from evaluationimprovement.domain.ids import IdempotencyKey, RunId


def _drive_run_to_passed(container: Container, run_key: str) -> RunId:
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=f"approval-consume-{run_key}", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
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


def _pending_approval_candidate(container: Container, seed: str):
    source_run_id = _drive_run_to_passed(container, f"approval-consume-source-{seed}")
    candidate = container.create_improvement_candidate_service.create(CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=source_run_id, source_failure_cluster_id="c1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey(f"idem-{seed}"),
    ))
    benchmark_run_id = _drive_run_to_passed(container, f"approval-consume-benchmark-{seed}")
    container.create_improvement_candidate_service.record_benchmark(RecordCandidateBenchmarkCommand(
        candidate_id=candidate.candidate_id, benchmark_run_id=benchmark_run_id, actor="author-1", correlation_id="corr-1",
    ))
    return container.create_improvement_candidate_service.request_approval(RequestCandidateApprovalCommand(
        candidate_id=candidate.candidate_id, actor="author-1", correlation_id="corr-1",
    ))


@pytest.mark.unit
def test_approval_granted_for_one_of_our_own_requests_approves_the_candidate(container: Container) -> None:
    candidate = _pending_approval_candidate(container, "granted-1")
    assert candidate.approval_request_id is not None

    applied = container.consume_approval_decision_event_service.consume_granted(ConsumeApprovalGrantedCommand(
        event_id="evt-approval-1", approval_request_id=candidate.approval_request_id, source_domain="evaluation-improvement",
        source_request_id=str(candidate.candidate_id), decided_by="approver-1", correlation_id="corr-1",
    ))
    assert applied is True

    updated = container.create_improvement_candidate_service.find_candidate(candidate.candidate_id)
    assert updated.status.value == "APPROVED"
    assert updated.approved_by == "approver-1"


@pytest.mark.unit
def test_approval_denied_for_one_of_our_own_requests_rejects_the_candidate(container: Container) -> None:
    candidate = _pending_approval_candidate(container, "denied-1")
    assert candidate.approval_request_id is not None

    applied = container.consume_approval_decision_event_service.consume_denied(ConsumeApprovalDeniedCommand(
        event_id="evt-approval-2", approval_request_id=candidate.approval_request_id, source_domain="evaluation-improvement",
        source_request_id=str(candidate.candidate_id), decided_by="approver-1", reason="risk too high", correlation_id="corr-1",
    ))
    assert applied is True

    updated = container.create_improvement_candidate_service.find_candidate(candidate.candidate_id)
    assert updated.status.value == "REJECTED"


@pytest.mark.unit
def test_a_decision_for_a_different_source_domain_is_not_applied(container: Container) -> None:
    candidate = _pending_approval_candidate(container, "other-domain-1")
    applied = container.consume_approval_decision_event_service.consume_granted(ConsumeApprovalGrantedCommand(
        event_id="evt-approval-3", approval_request_id=candidate.approval_request_id, source_domain="tool-integration-gateway",
        source_request_id="some-tool-request", decided_by="approver-1", correlation_id="corr-1",
    ))
    assert applied is False
    updated = container.create_improvement_candidate_service.find_candidate(candidate.candidate_id)
    assert updated.status.value == "PENDING_APPROVAL"


@pytest.mark.unit
def test_a_redelivered_decision_is_not_applied_twice(container: Container) -> None:
    candidate = _pending_approval_candidate(container, "redelivered-1")
    command = ConsumeApprovalGrantedCommand(
        event_id="evt-approval-4", approval_request_id=candidate.approval_request_id, source_domain="evaluation-improvement",
        source_request_id=str(candidate.candidate_id), decided_by="approver-1", correlation_id="corr-1",
    )
    first = container.consume_approval_decision_event_service.consume_granted(command)
    second = container.consume_approval_decision_event_service.consume_granted(command)
    assert first is True
    assert second is False


@pytest.mark.unit
def test_an_unknown_approval_request_id_is_not_applied(container: Container) -> None:
    applied = container.consume_approval_decision_event_service.consume_granted(ConsumeApprovalGrantedCommand(
        event_id="evt-approval-5", approval_request_id="approval-does-not-exist", source_domain="evaluation-improvement",
        source_request_id="unknown", decided_by="approver-1", correlation_id="corr-1",
    ))
    assert applied is False


@pytest.mark.unit
def test_a_self_approval_decision_is_recorded_as_a_poison_event(container: Container) -> None:
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling
    §"Poison Event": 06 itself should refuse a self-approval, but if it somehow
    doesn't, this consumer must not silently apply it either — the domain's own
    SelfApprovalNotAllowedException still fires, and this time it is caught,
    recorded, and never marked processed.
    """
    candidate = _pending_approval_candidate(container, "poison-self-approve-1")
    assert candidate.approval_request_id is not None

    with pytest.raises(PoisonApprovalDecisionEventException):
        container.consume_approval_decision_event_service.consume_granted(ConsumeApprovalGrantedCommand(
            event_id="evt-approval-poison-1", approval_request_id=candidate.approval_request_id,
            source_domain="evaluation-improvement", source_request_id=str(candidate.candidate_id),
            decided_by="author-1",  # the candidate's own creator
            correlation_id="corr-1",
        ))

    updated = container.create_improvement_candidate_service.find_candidate(candidate.candidate_id)
    assert updated.status.value == "PENDING_APPROVAL"

    poisoned = container.poison_event_repository.find_all(10)
    assert len(poisoned) == 1
    assert poisoned[0].event_id == "evt-approval-poison-1"
    assert poisoned[0].consumer_name == "consume_approval_decision_event"
    assert "self" in poisoned[0].error_message.lower() or "approv" in poisoned[0].error_message.lower()

    # Never marked processed — the same event_id stays replayable.
    assert container.processed_event_repository.is_processed("evt-approval-poison-1", "consume_approval_decision_event") is False


@pytest.mark.unit
def test_a_late_decision_for_an_already_terminal_candidate_is_recorded_as_a_poison_event(container: Container) -> None:
    candidate = _pending_approval_candidate(container, "poison-late-1")
    assert candidate.approval_request_id is not None

    # Reaches REJECTED through the ordinary REST-driven path first.
    container.create_improvement_candidate_service.reject(RejectCandidateCommand(
        candidate_id=candidate.candidate_id, reason="superseded", actor="admin-1", correlation_id="corr-1",
    ))

    with pytest.raises(PoisonApprovalDecisionEventException):
        container.consume_approval_decision_event_service.consume_granted(ConsumeApprovalGrantedCommand(
            event_id="evt-approval-poison-2", approval_request_id=candidate.approval_request_id,
            source_domain="evaluation-improvement", source_request_id=str(candidate.candidate_id), decided_by="approver-1",
            correlation_id="corr-1",
        ))

    poisoned = container.poison_event_repository.find_all(10)
    assert any(p.event_id == "evt-approval-poison-2" for p in poisoned)
