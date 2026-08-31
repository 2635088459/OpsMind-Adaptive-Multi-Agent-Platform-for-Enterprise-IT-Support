from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    ApproveCandidateCommand,
    CreateDatasetCommand,
    CreateImprovementCandidateCommand,
    PublishDatasetCommand,
    RecordCandidateBenchmarkCommand,
    RequestCandidateApprovalCommand,
    RunCiGateCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.views import CiGateOutcome
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CandidateType, Criticality, RiskLevel
from evaluationimprovement.domain.exceptions import SelfApprovalNotAllowedException
from evaluationimprovement.domain.ids import IdempotencyKey, RunId


def _create_command(source_run_id: RunId, idempotency_key: str = "idem-1") -> CreateImprovementCandidateCommand:
    return CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=source_run_id, source_failure_cluster_id="cluster-1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey(idempotency_key),
    )


def _drive_run_to_passed(container: Container, run_key: str) -> RunId:
    """Mirrors test_ci_evaluation_gate_service.py's own `_publish_dataset` +
    `run_gate` pattern — the shortest path to a real EvaluationRun with a terminal
    PASSED release-gate decision, which SPEC-EI-025 now requires `record_benchmark`
    to be bound to (never a bare caller-supplied `passed: bool` claim).
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=f"candidate-benchmark-{run_key}", version="1", domain="IDENTITY_ACCESS", scenario_tags=(),
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
    outcome: CiGateOutcome = container.ci_evaluation_gate_service.run_gate(RunCiGateCommand(
        run_key=run_key, dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    assert outcome.passed is True
    return outcome.run_id


@pytest.mark.unit
def test_repeated_idempotency_key_with_same_payload_returns_same_candidate(container: Container) -> None:
    run_id = _drive_run_to_passed(container, "candidate-idem-run-001")
    command = _create_command(run_id)
    first = container.create_improvement_candidate_service.create(command)
    second = container.create_improvement_candidate_service.create(command)
    assert first.candidate_id == second.candidate_id


@pytest.mark.unit
def test_same_natural_key_with_a_different_idempotency_key_still_converges(container: Container) -> None:
    """09-concurrency-and-idempotency §"幂等键": `sourceRunId:failureClusterId:
    targetComponent` — a natural-key match converges independent of the caller-
    supplied idempotency_key.
    """
    run_id = _drive_run_to_passed(container, "candidate-natural-key-run-001")
    first_command = _create_command(run_id, "idem-1")
    first = container.create_improvement_candidate_service.create(first_command)

    second_command = CreateImprovementCandidateCommand(
        candidate_type=first_command.candidate_type, source_run_id=first_command.source_run_id,
        source_failure_cluster_id=first_command.source_failure_cluster_id, target_component=first_command.target_component,
        proposed_change=first_command.proposed_change, risk_level=first_command.risk_level, created_by="author-2",
        actor="author-2", correlation_id="corr-2", idempotency_key=IdempotencyKey("idem-2"),
    )
    second = container.create_improvement_candidate_service.create(second_command)
    assert first.candidate_id == second.candidate_id


@pytest.mark.unit
def test_record_benchmark_derives_passed_from_the_bound_runs_own_gate_decision(container: Container) -> None:
    """SPEC-EI-025 (candidate-benchmark-binding-gate-enforcement): `passed` is never a
    bare caller claim — it is derived from `benchmark_run_id`'s own terminal
    PASSED/FAILED release-gate decision.
    """
    source_run_id = _drive_run_to_passed(container, "candidate-source-run-001")
    benchmark_run_id = _drive_run_to_passed(container, "candidate-benchmark-run-001")
    candidate = container.create_improvement_candidate_service.create(_create_command(source_run_id, "idem-benchmark-1"))

    view = container.create_improvement_candidate_service.record_benchmark(RecordCandidateBenchmarkCommand(
        candidate_id=candidate.candidate_id, benchmark_run_id=benchmark_run_id, actor="author-1", correlation_id="corr-1",
    ))
    assert view.benchmark_run_id == benchmark_run_id
    assert view.benchmark_passed is True
    assert view.status.value == "BENCHMARKING"


@pytest.mark.unit
def test_creator_cannot_approve_own_candidate_at_application_layer(container: Container) -> None:
    source_run_id = _drive_run_to_passed(container, "candidate-self-approve-source-run-001")
    benchmark_run_id = _drive_run_to_passed(container, "candidate-self-approve-benchmark-run-001")
    candidate = container.create_improvement_candidate_service.create(_create_command(source_run_id))
    container.create_improvement_candidate_service.record_benchmark(
        RecordCandidateBenchmarkCommand(
            candidate_id=candidate.candidate_id, benchmark_run_id=benchmark_run_id, actor="author-1", correlation_id="corr-1",
        ),
    )
    container.create_improvement_candidate_service.request_approval(
        RequestCandidateApprovalCommand(candidate_id=candidate.candidate_id, actor="author-1", correlation_id="corr-1"),
    )
    with pytest.raises(SelfApprovalNotAllowedException):
        container.create_improvement_candidate_service.approve(ApproveCandidateCommand(
            candidate_id=candidate.candidate_id, approved_by="author-1", actor="author-1", correlation_id="corr-1",
        ))
