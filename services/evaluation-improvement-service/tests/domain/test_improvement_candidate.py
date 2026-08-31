from __future__ import annotations

from datetime import UTC, datetime

import pytest

from evaluationimprovement.domain.enums import CandidateType, RiskLevel
from evaluationimprovement.domain.exceptions import (
    CandidateMissingApprovalException,
    CandidateMissingBenchmarkException,
    SelfApprovalNotAllowedException,
)
from evaluationimprovement.domain.ids import CandidateId, RunId
from evaluationimprovement.domain.improvement_candidate import ImprovementCandidate
from evaluationimprovement.domain.state_machine import InvalidStateTransitionException
from evaluationimprovement.domain.values import CanaryPlan, CanaryStage

_NOW = datetime.now(UTC)
_BENCHMARK_RUN_ID = RunId.new_id()


def _candidate() -> ImprovementCandidate:
    return ImprovementCandidate.create(
        CandidateId.new_id(), CandidateType.PROMPT_CHANGE, RunId.new_id(), "cluster-1", "identity-agent-prompt",
        {"promptDiff": "..."}, RiskLevel.MEDIUM, "author-1", _NOW,
    )


def _plan() -> CanaryPlan:
    return CanaryPlan("v1", (CanaryStage(5.0, 30, 0.05, 50), CanaryStage(25.0, 30, 0.05, 200)))


@pytest.mark.unit
def test_canary_stage_requires_a_positive_sample_size() -> None:
    """SPEC-EI-027 (canary-plan-rollout-state-machine) / phase-06 own "强制约束":
    "Canary 必须有流量比例、时间窗、sample size 和 rollback thresholds."
    """
    with pytest.raises(ValueError, match="sampleSize"):
        CanaryStage(5.0, 30, 0.05, 0)


@pytest.mark.unit
def test_request_approval_requires_a_passed_benchmark() -> None:
    candidate = _candidate().start_benchmarking(_NOW)
    with pytest.raises(CandidateMissingBenchmarkException):
        candidate.request_approval(_NOW)


@pytest.mark.unit
def test_full_happy_path_to_promoted() -> None:
    candidate = (
        _candidate().start_benchmarking(_NOW).record_benchmark_result(_BENCHMARK_RUN_ID, True, _NOW).request_approval(_NOW)
        .bind_approval_request("approval-1", _NOW).approve("approver-1", _NOW)
    )
    assert candidate.status.value == "APPROVED"
    candidate = candidate.start_canary(_plan(), _NOW)
    assert candidate.canary_status.value == "PLANNED"
    candidate = candidate.canary_activate(_NOW).canary_expand(_NOW).canary_succeed(_NOW)
    assert candidate.canary_status.value == "SUCCEEDED"
    promoted = candidate.promote("agent-runtime:2026.08.27", _NOW)
    assert promoted.status.value == "PROMOTED"
    assert promoted.promoted_version == "agent-runtime:2026.08.27"


@pytest.mark.unit
def test_approve_by_creator_is_forbidden() -> None:
    candidate = (
        _candidate().start_benchmarking(_NOW).record_benchmark_result(_BENCHMARK_RUN_ID, True, _NOW).request_approval(_NOW)
        .bind_approval_request("approval-1", _NOW)
    )
    with pytest.raises(SelfApprovalNotAllowedException):
        candidate.approve("author-1", _NOW)


@pytest.mark.unit
def test_start_canary_requires_a_bound_approval_reference() -> None:
    # Reaches APPROVED without ever calling bind_approval_request() — the
    # application-layer service always calls it (08-transaction-and-outbox §"Candidate
    # 状态进入 PENDING_APPROVAL 时，必须记录 06 approval request 引用"), but the domain
    # aggregate itself must still refuse a Canary start if it somehow didn't happen.
    candidate = (
        _candidate().start_benchmarking(_NOW).record_benchmark_result(_BENCHMARK_RUN_ID, True, _NOW).request_approval(_NOW)
        .approve("approver-1", _NOW)
    )
    with pytest.raises(CandidateMissingApprovalException):
        candidate.start_canary(_plan(), _NOW)


@pytest.mark.unit
def test_failed_benchmark_can_be_rejected() -> None:
    candidate = _candidate().start_benchmarking(_NOW).record_benchmark_result(_BENCHMARK_RUN_ID, False, _NOW)
    rejected = candidate.reject(_NOW)
    assert rejected.status.value == "REJECTED"


@pytest.mark.unit
def test_rejected_candidate_is_terminal() -> None:
    rejected = _candidate().start_benchmarking(_NOW).reject(_NOW)
    with pytest.raises(InvalidStateTransitionException):
        rejected.start_benchmarking(_NOW)


@pytest.mark.unit
def test_canary_failure_and_rollback_sequence() -> None:
    candidate = (
        _candidate().start_benchmarking(_NOW).record_benchmark_result(_BENCHMARK_RUN_ID, True, _NOW).request_approval(_NOW)
        .bind_approval_request("approval-1", _NOW).approve("approver-1", _NOW).start_canary(_plan(), _NOW)
        .canary_activate(_NOW)
    )
    failed = candidate.canary_fail(_NOW)
    rollback_requested = failed.canary_request_rollback(_NOW)
    assert rollback_requested.canary_status.value == "ROLLBACK_REQUESTED"
    rolled_back = rollback_requested.rollback(_NOW)
    assert rolled_back.status.value == "ROLLED_BACK"
    assert rolled_back.canary_status.value == "ROLLED_BACK"


@pytest.mark.unit
def test_promoted_candidate_can_still_roll_back() -> None:
    candidate = (
        _candidate().start_benchmarking(_NOW).record_benchmark_result(_BENCHMARK_RUN_ID, True, _NOW).request_approval(_NOW)
        .bind_approval_request("approval-1", _NOW).approve("approver-1", _NOW).start_canary(_plan(), _NOW)
        .canary_activate(_NOW).canary_expand(_NOW).canary_succeed(_NOW).promote("v-rc1", _NOW)
    )
    rolled_back = candidate.rollback(_NOW)
    assert rolled_back.status.value == "ROLLED_BACK"
