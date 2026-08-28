from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AdvanceCanaryCommand,
    ApproveCandidateCommand,
    CanaryStageInput,
    CreateImprovementCandidateCommand,
    RecordCandidateBenchmarkCommand,
    RequestCandidateApprovalCommand,
    RequestCanaryRollbackCommand,
    StartCanaryCommand,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CandidateType, RiskLevel
from evaluationimprovement.domain.ids import IdempotencyKey, RunId


def _approved_candidate(container: Container):
    candidate = container.create_improvement_candidate_service.create(CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=RunId.new_id(), source_failure_cluster_id="c1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("idem-c1"),
    ))
    container.create_improvement_candidate_service.record_benchmark(RecordCandidateBenchmarkCommand(candidate_id=candidate.candidate_id, passed=True, actor="author-1", correlation_id="corr-1"))
    container.create_improvement_candidate_service.request_approval(RequestCandidateApprovalCommand(candidate_id=candidate.candidate_id, actor="author-1", correlation_id="corr-1"))
    return container.create_improvement_candidate_service.approve(ApproveCandidateCommand(candidate_id=candidate.candidate_id, approved_by="approver-1", actor="approver-1", correlation_id="corr-1"))


@pytest.mark.unit
def test_start_canary_is_idempotent_under_the_same_key(container: Container) -> None:
    candidate = _approved_candidate(container)
    command = StartCanaryCommand(
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05),), actor="admin-1",
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
        stages=(CanaryStageInput(5.0, 30, 0.05), CanaryStageInput(25.0, 30, 0.05)), actor="admin-1",
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
        candidate_id=candidate.candidate_id, plan_version="v1", stages=(CanaryStageInput(5.0, 30, 0.05),), actor="admin-1",
        correlation_id="corr-1", idempotency_key=IdempotencyKey("canary-start-3"),
    ))
    rollback_requested = container.manage_canary_service.request_rollback(RequestCanaryRollbackCommand(
        candidate_id=candidate.candidate_id, reason="error rate spike", actor="admin-1", correlation_id="corr-1",
        idempotency_key=IdempotencyKey("rollback-1"),
    ))
    assert rollback_requested.canary_status.value == "ROLLBACK_REQUESTED"
