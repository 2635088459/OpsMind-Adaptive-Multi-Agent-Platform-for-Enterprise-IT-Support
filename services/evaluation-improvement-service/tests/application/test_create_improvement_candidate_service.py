from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    ApproveCandidateCommand,
    CreateImprovementCandidateCommand,
    RecordCandidateBenchmarkCommand,
    RequestCandidateApprovalCommand,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CandidateType, RiskLevel
from evaluationimprovement.domain.exceptions import SelfApprovalNotAllowedException
from evaluationimprovement.domain.ids import IdempotencyKey, RunId


def _create_command(idempotency_key: str = "idem-1") -> CreateImprovementCandidateCommand:
    return CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=RunId.new_id(), source_failure_cluster_id="cluster-1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey(idempotency_key),
    )


@pytest.mark.unit
def test_repeated_idempotency_key_with_same_payload_returns_same_candidate(container: Container) -> None:
    command = _create_command()
    first = container.create_improvement_candidate_service.create(command)
    second = container.create_improvement_candidate_service.create(command)
    assert first.candidate_id == second.candidate_id


@pytest.mark.unit
def test_same_natural_key_with_a_different_idempotency_key_still_converges(container: Container) -> None:
    """09-concurrency-and-idempotency §"幂等键": `sourceRunId:failureClusterId:
    targetComponent` — a natural-key match converges independent of the caller-
    supplied idempotency_key.
    """
    first_command = _create_command("idem-1")
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
def test_creator_cannot_approve_own_candidate_at_application_layer(container: Container) -> None:
    candidate = container.create_improvement_candidate_service.create(_create_command())
    container.create_improvement_candidate_service.record_benchmark(
        RecordCandidateBenchmarkCommand(candidate_id=candidate.candidate_id, passed=True, actor="author-1", correlation_id="corr-1"),
    )
    container.create_improvement_candidate_service.request_approval(
        RequestCandidateApprovalCommand(candidate_id=candidate.candidate_id, actor="author-1", correlation_id="corr-1"),
    )
    with pytest.raises(SelfApprovalNotAllowedException):
        container.create_improvement_candidate_service.approve(ApproveCandidateCommand(
            candidate_id=candidate.candidate_id, approved_by="author-1", actor="author-1", correlation_id="corr-1",
        ))
