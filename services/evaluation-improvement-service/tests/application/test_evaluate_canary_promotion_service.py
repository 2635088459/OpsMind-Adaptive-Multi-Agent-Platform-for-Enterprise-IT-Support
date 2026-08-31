"""SPEC-EI-029 (promotion-criteria-rollback-request): EvaluateCanaryPromotionService —
a pure recommendation derived from a candidate's own current canary stage thresholds
and its bound online samples, never an executed action.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    ApproveCandidateCommand,
    CanaryStageInput,
    CreateDatasetCommand,
    CreateImprovementCandidateCommand,
    PublishDatasetCommand,
    RecordCandidateBenchmarkCommand,
    RequestCandidateApprovalCommand,
    RunCiGateCommand,
    StartCanaryCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.records import OnlineEvaluationSample
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CandidateType, Criticality, OnlineSampleStatus, RiskLevel, ScoreFailureCode
from evaluationimprovement.domain.ids import CandidateId, IdempotencyKey, RunId


def _drive_run_to_passed(container: Container, run_key: str) -> RunId:
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=f"promo-{run_key}", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
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


def _canarying_candidate(container: Container, sample_size: int, rollback_error_rate_threshold: float) -> CandidateId:
    source_run_id = _drive_run_to_passed(container, f"promo-source-{uuid.uuid4()}")
    candidate = container.create_improvement_candidate_service.create(CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=source_run_id, source_failure_cluster_id="c1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey(f"idem-{uuid.uuid4()}"),
    ))
    benchmark_run_id = _drive_run_to_passed(container, f"promo-benchmark-{uuid.uuid4()}")
    container.create_improvement_candidate_service.record_benchmark(RecordCandidateBenchmarkCommand(
        candidate_id=candidate.candidate_id, benchmark_run_id=benchmark_run_id, actor="author-1", correlation_id="corr-1",
    ))
    container.create_improvement_candidate_service.request_approval(RequestCandidateApprovalCommand(
        candidate_id=candidate.candidate_id, actor="author-1", correlation_id="corr-1",
    ))
    container.create_improvement_candidate_service.approve(ApproveCandidateCommand(
        candidate_id=candidate.candidate_id, approved_by="approver-1", actor="approver-1", correlation_id="corr-1",
    ))
    container.manage_canary_service.start_canary(StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1",
        stages=(CanaryStageInput(5.0, 30, rollback_error_rate_threshold, sample_size),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey(f"canary-start-{uuid.uuid4()}"),
    ))
    return candidate.candidate_id


def _scored_sample(candidate_id: CandidateId, *, composite_score: float | None, failure_code: ScoreFailureCode | None = None) -> OnlineEvaluationSample:
    return OnlineEvaluationSample(
        sample_id=uuid.uuid4(), candidate_id=candidate_id.value, target_version="agent-runtime:rc1",
        source_event_type="WORKFLOW_COMPLETED", source_trace_ref="trace-1", redacted_context={},
        status=OnlineSampleStatus.SCORED, collected_at=datetime.now(UTC), scored_at=datetime.now(UTC),
        composite_score=composite_score, failure_code=failure_code,
    )


@pytest.mark.unit
def test_unknown_candidate_raises_not_found(container: Container) -> None:
    from evaluationimprovement.application.exceptions import CandidateNotFoundException

    with pytest.raises(CandidateNotFoundException):
        container.evaluate_canary_promotion_service.evaluate(CandidateId.new_id())


@pytest.mark.unit
def test_a_candidate_not_currently_canarying_is_never_eligible(container: Container) -> None:
    source_run_id = _drive_run_to_passed(container, f"promo-not-canarying-{uuid.uuid4()}")
    candidate = container.create_improvement_candidate_service.create(CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=source_run_id, source_failure_cluster_id="c1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey(f"idem-{uuid.uuid4()}"),
    ))
    decision = container.evaluate_canary_promotion_service.evaluate(candidate.candidate_id)
    assert decision.eligible_to_advance is False
    assert decision.recommend_rollback is False


@pytest.mark.unit
def test_insufficient_samples_is_not_eligible_and_does_not_recommend_rollback(container: Container) -> None:
    candidate_id = _canarying_candidate(container, sample_size=5, rollback_error_rate_threshold=0.1)
    container.online_sample_repository.save(_scored_sample(candidate_id, composite_score=0.9))

    decision = container.evaluate_canary_promotion_service.evaluate(candidate_id)
    assert decision.eligible_to_advance is False
    assert decision.recommend_rollback is False
    assert decision.sample_count == 1
    assert decision.required_sample_size == 5
    assert "insufficient" in decision.reason


@pytest.mark.unit
def test_a_low_error_rate_within_threshold_is_eligible_to_advance(container: Container) -> None:
    candidate_id = _canarying_candidate(container, sample_size=4, rollback_error_rate_threshold=0.3)
    for _ in range(4):
        container.online_sample_repository.save(_scored_sample(candidate_id, composite_score=0.9))

    decision = container.evaluate_canary_promotion_service.evaluate(candidate_id)
    assert decision.eligible_to_advance is True
    assert decision.recommend_rollback is False
    assert decision.error_rate == pytest.approx(0.0)


@pytest.mark.unit
def test_a_high_error_rate_recommends_rollback_not_advance(container: Container) -> None:
    candidate_id = _canarying_candidate(container, sample_size=4, rollback_error_rate_threshold=0.2)
    container.online_sample_repository.save(_scored_sample(candidate_id, composite_score=0.9))
    container.online_sample_repository.save(_scored_sample(candidate_id, composite_score=0.9))
    container.online_sample_repository.save(_scored_sample(candidate_id, composite_score=0.1))
    container.online_sample_repository.save(_scored_sample(candidate_id, composite_score=None, failure_code=ScoreFailureCode.UNSCORED))

    decision = container.evaluate_canary_promotion_service.evaluate(candidate_id)
    assert decision.eligible_to_advance is False
    assert decision.recommend_rollback is True
    assert decision.error_rate == pytest.approx(0.5)
