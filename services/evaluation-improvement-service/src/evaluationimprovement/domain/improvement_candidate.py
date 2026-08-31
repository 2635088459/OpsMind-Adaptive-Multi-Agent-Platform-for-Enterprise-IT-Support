"""01-domain-model §"ImprovementCandidate". 03-state-machine §"ImprovementCandidate"
and §"Canary" — Canary is modelled as a sub-state carried on the same aggregate
(`canary_status`/`canary_plan`), not a separate top-level aggregate: 01-domain-model's
own field list gives ImprovementCandidate a `canaryPlan` field directly, and
07-data-model's `improvement_candidates` table stores `canary_plan jsonb` on the same
row rather than a separate table.
"""

from __future__ import annotations

import dataclasses
from datetime import datetime

from evaluationimprovement.domain.enums import CandidateStatus, CandidateType, CanaryStatus, RiskLevel
from evaluationimprovement.domain.exceptions import (
    CandidateMissingApprovalException,
    CandidateMissingBenchmarkException,
    SelfApprovalNotAllowedException,
)
from evaluationimprovement.domain.ids import CandidateId, RunId
from evaluationimprovement.domain.state_machine import StateMachine
from evaluationimprovement.domain.values import CanaryPlan

_CANDIDATE_TRANSITIONS: dict[CandidateStatus, frozenset[CandidateStatus]] = {
    CandidateStatus.DRAFT: frozenset({CandidateStatus.BENCHMARKING}),
    CandidateStatus.BENCHMARKING: frozenset({CandidateStatus.PENDING_APPROVAL, CandidateStatus.REJECTED}),
    CandidateStatus.PENDING_APPROVAL: frozenset({CandidateStatus.APPROVED, CandidateStatus.REJECTED}),
    CandidateStatus.APPROVED: frozenset({CandidateStatus.CANARYING}),
    CandidateStatus.CANARYING: frozenset({CandidateStatus.PROMOTED, CandidateStatus.ROLLED_BACK}),
    CandidateStatus.PROMOTED: frozenset({CandidateStatus.ROLLED_BACK}),
    CandidateStatus.REJECTED: frozenset(),
    CandidateStatus.ROLLED_BACK: frozenset(),
}
_CANDIDATE_STATE_MACHINE: StateMachine[CandidateStatus] = StateMachine("ImprovementCandidate", _CANDIDATE_TRANSITIONS)

_CANARY_TRANSITIONS: dict[CanaryStatus, frozenset[CanaryStatus]] = {
    CanaryStatus.PLANNED: frozenset({CanaryStatus.ACTIVE}),
    CanaryStatus.ACTIVE: frozenset({CanaryStatus.EXPANDING, CanaryStatus.PAUSED, CanaryStatus.FAILED}),
    CanaryStatus.EXPANDING: frozenset({CanaryStatus.SUCCEEDED, CanaryStatus.PAUSED, CanaryStatus.FAILED}),
    CanaryStatus.SUCCEEDED: frozenset(),
    CanaryStatus.PAUSED: frozenset({CanaryStatus.ACTIVE, CanaryStatus.EXPANDING, CanaryStatus.FAILED}),
    CanaryStatus.FAILED: frozenset({CanaryStatus.ROLLBACK_REQUESTED}),
    CanaryStatus.ROLLBACK_REQUESTED: frozenset({CanaryStatus.ROLLED_BACK}),
    CanaryStatus.ROLLED_BACK: frozenset(),
}
_CANARY_STATE_MACHINE: StateMachine[CanaryStatus] = StateMachine("Canary", _CANARY_TRANSITIONS)


@dataclasses.dataclass(frozen=True, slots=True)
class ImprovementCandidate:
    candidate_id: CandidateId
    candidate_type: CandidateType
    source_run_id: RunId
    source_failure_cluster_id: str | None
    target_component: str
    proposed_change: dict[str, object]
    risk_level: RiskLevel
    status: CandidateStatus
    created_by: str
    created_at: datetime
    updated_at: datetime
    benchmark_run_id: RunId | None = None
    benchmark_passed: bool = False
    approval_request_id: str | None = None
    approved_by: str | None = None
    canary_plan: CanaryPlan | None = None
    canary_status: CanaryStatus | None = None
    promoted_version: str | None = None

    @staticmethod
    def create(
        candidate_id: CandidateId, candidate_type: CandidateType, source_run_id: RunId,
        source_failure_cluster_id: str | None, target_component: str, proposed_change: dict[str, object],
        risk_level: RiskLevel, created_by: str, now: datetime,
    ) -> "ImprovementCandidate":
        if not target_component or not target_component.strip():
            raise ValueError("targetComponent must not be blank")
        if not proposed_change:
            raise ValueError("proposedChange must not be empty")
        return ImprovementCandidate(
            candidate_id=candidate_id, candidate_type=candidate_type, source_run_id=source_run_id,
            source_failure_cluster_id=source_failure_cluster_id, target_component=target_component,
            proposed_change=proposed_change, risk_level=risk_level, status=CandidateStatus.DRAFT, created_by=created_by,
            created_at=now, updated_at=now,
        )

    def start_benchmarking(self, now: datetime) -> "ImprovementCandidate":
        _CANDIDATE_STATE_MACHINE.assert_transition(self.status, CandidateStatus.BENCHMARKING)
        return dataclasses.replace(self, status=CandidateStatus.BENCHMARKING, updated_at=now)

    def record_benchmark_result(self, benchmark_run_id: RunId, passed: bool, now: datetime) -> "ImprovementCandidate":
        """SPEC-EI-025 (candidate-benchmark-binding-gate-enforcement) / phase-05 own
        "强制约束": "Candidate 必须绑定...benchmark result." `benchmark_run_id` is the
        actual EvaluationRun that benchmarked this candidate's proposed change — the
        caller (CreateImprovementCandidateService) derives `passed` from that run's
        own terminal PASSED/FAILED release-gate status, never accepts it as a bare
        caller-supplied claim. `passed=False` records the benchmark outcome but does
        not itself transition status — the caller still calls reject() explicitly,
        keeping "what happened" (benchmark_passed) and "what state we're in" (status)
        as two separate, individually-auditable facts.
        """
        return dataclasses.replace(self, benchmark_run_id=benchmark_run_id, benchmark_passed=passed, updated_at=now)

    def request_approval(self, now: datetime) -> "ImprovementCandidate":
        """02-business-invariants INV-EI-002: benchmark (release gate) must pass before
        candidate promotion proceeds toward approval.
        """
        _CANDIDATE_STATE_MACHINE.assert_transition(self.status, CandidateStatus.PENDING_APPROVAL)
        if not self.benchmark_passed:
            raise CandidateMissingBenchmarkException()
        return dataclasses.replace(self, status=CandidateStatus.PENDING_APPROVAL, updated_at=now)

    def bind_approval_request(self, approval_request_id: str, now: datetime) -> "ImprovementCandidate":
        """08-transaction-and-outbox: "Candidate 状态进入 PENDING_APPROVAL 时，必须记录 06
        approval request 引用."
        """
        if not approval_request_id or not approval_request_id.strip():
            raise ValueError("approvalRequestId must not be blank")
        return dataclasses.replace(self, approval_request_id=approval_request_id, updated_at=now)

    def approve(self, approved_by: str, now: datetime) -> "ImprovementCandidate":
        """11-security: "自动生成的 candidate 不能自我审批." A candidate can never be
        approved by the same actor who created it.
        """
        _CANDIDATE_STATE_MACHINE.assert_transition(self.status, CandidateStatus.APPROVED)
        if approved_by == self.created_by:
            raise SelfApprovalNotAllowedException()
        return dataclasses.replace(self, status=CandidateStatus.APPROVED, approved_by=approved_by, updated_at=now)

    def reject(self, now: datetime) -> "ImprovementCandidate":
        _CANDIDATE_STATE_MACHINE.assert_transition(self.status, CandidateStatus.REJECTED)
        return dataclasses.replace(self, status=CandidateStatus.REJECTED, updated_at=now)

    def start_canary(self, plan: CanaryPlan, now: datetime) -> "ImprovementCandidate":
        """02-business-invariants INV-EI-002: Canary requires a bound 06 approval
        reference.
        """
        _CANDIDATE_STATE_MACHINE.assert_transition(self.status, CandidateStatus.CANARYING)
        if not self.approval_request_id:
            raise CandidateMissingApprovalException()
        return dataclasses.replace(
            self, status=CandidateStatus.CANARYING, canary_plan=plan, canary_status=CanaryStatus.PLANNED, updated_at=now,
        )

    def promote(self, promoted_version: str, now: datetime) -> "ImprovementCandidate":
        _CANDIDATE_STATE_MACHINE.assert_transition(self.status, CandidateStatus.PROMOTED)
        return dataclasses.replace(
            self, status=CandidateStatus.PROMOTED, promoted_version=promoted_version, updated_at=now,
        )

    def rollback(self, now: datetime) -> "ImprovementCandidate":
        _CANDIDATE_STATE_MACHINE.assert_transition(self.status, CandidateStatus.ROLLED_BACK)
        return dataclasses.replace(
            self, status=CandidateStatus.ROLLED_BACK, canary_status=CanaryStatus.ROLLED_BACK, updated_at=now,
        )

    # Canary sub-state transitions -----------------------------------------------
    def _with_canary(self, target: CanaryStatus, now: datetime) -> "ImprovementCandidate":
        if self.canary_status is None:
            raise ValueError("candidate has no active canary plan")
        _CANARY_STATE_MACHINE.assert_transition(self.canary_status, target)
        return dataclasses.replace(self, canary_status=target, updated_at=now)

    def canary_activate(self, now: datetime) -> "ImprovementCandidate":
        return self._with_canary(CanaryStatus.ACTIVE, now)

    def canary_expand(self, now: datetime) -> "ImprovementCandidate":
        return self._with_canary(CanaryStatus.EXPANDING, now)

    def canary_succeed(self, now: datetime) -> "ImprovementCandidate":
        return self._with_canary(CanaryStatus.SUCCEEDED, now)

    def canary_pause(self, now: datetime) -> "ImprovementCandidate":
        return self._with_canary(CanaryStatus.PAUSED, now)

    def canary_fail(self, now: datetime) -> "ImprovementCandidate":
        return self._with_canary(CanaryStatus.FAILED, now)

    def canary_request_rollback(self, now: datetime) -> "ImprovementCandidate":
        return self._with_canary(CanaryStatus.ROLLBACK_REQUESTED, now)

    @property
    def is_final(self) -> bool:
        return _CANDIDATE_STATE_MACHINE.is_terminal(self.status)
