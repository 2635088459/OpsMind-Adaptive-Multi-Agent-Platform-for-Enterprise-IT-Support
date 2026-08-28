"""13-package-and-class-design §"应用层": CreateImprovementCandidateService, the sole
implementation of CreateImprovementCandidateUseCase and CandidateQueryUseCase.
04-use-cases UC-EI-004: "生成改进候选."
"""

from __future__ import annotations

import uuid
from datetime import datetime

from evaluationimprovement.application.commands import (
    ApproveCandidateCommand,
    CreateImprovementCandidateCommand,
    PromoteCandidateCommand,
    RecordCandidateBenchmarkCommand,
    RejectCandidateCommand,
    RequestCandidateApprovalCommand,
)
from evaluationimprovement.application.exceptions import CandidateNotFoundException
from evaluationimprovement.application.outbox_codec import build_outbox_record, to_correlation_id
from evaluationimprovement.application.ports_out import (
    AuditRecordRepository,
    ClockPort,
    CommandIdempotencyRepository,
    ImprovementCandidateRepository,
    OutboxRepository,
    PolicyApprovalPort,
)
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.services.idempotency import CommandIdempotencyGuard
from evaluationimprovement.application.telemetry import EvaluationTelemetry
from evaluationimprovement.application.views import ImprovementCandidateView
from evaluationimprovement.domain.enums import CandidateStatus, CandidateType, CanaryStatus, RiskLevel
from evaluationimprovement.domain.events import ImprovementCandidateApproved, ImprovementCandidateCreated
from evaluationimprovement.domain.ids import CandidateId, RunId
from evaluationimprovement.domain.improvement_candidate import ImprovementCandidate

_COMMAND_TYPE = "create_improvement_candidate"


class CreateImprovementCandidateService:
    def __init__(
        self, candidate_repository: ImprovementCandidateRepository, command_idempotency_repository: CommandIdempotencyRepository,
        policy_approval_port: PolicyApprovalPort, outbox_repository: OutboxRepository,
        audit_record_repository: AuditRecordRepository, clock: ClockPort, telemetry: EvaluationTelemetry,
    ) -> None:
        self._candidate_repository = candidate_repository
        self._policy_approval_port = policy_approval_port
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._telemetry = telemetry
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def create(self, command: CreateImprovementCandidateCommand) -> ImprovementCandidateView:
        return self._idempotency_guard.run(
            command_type=_COMMAND_TYPE, target_id=None, idempotency_key=command.idempotency_key,
            request_payload=_request_payload(command), execute=lambda: self._do_create(command),
            to_dict=candidate_view_to_dict, from_dict=candidate_view_from_dict,
        )

    def _do_create(self, command: CreateImprovementCandidateCommand) -> ImprovementCandidateView:
        # 09-concurrency-and-idempotency §"幂等键": `sourceRunId:failureClusterId:
        # targetComponent` — a natural-key match converges on the existing candidate
        # the same way ExtractMemoryCandidateService's own source-hash dedup does,
        # independent of whatever caller-supplied idempotency_key wraps this call.
        existing = self._candidate_repository.find_by_natural_key(
            command.source_run_id, command.source_failure_cluster_id, command.target_component,
        )
        if existing is not None:
            return candidate_to_view(existing)

        now = self._clock.now()
        candidate = ImprovementCandidate.create(
            candidate_id=CandidateId.new_id(), candidate_type=command.candidate_type, source_run_id=command.source_run_id,
            source_failure_cluster_id=command.source_failure_cluster_id, target_component=command.target_component,
            proposed_change=command.proposed_change, risk_level=command.risk_level, created_by=command.created_by, now=now,
        )
        saved = self._candidate_repository.save(candidate, expected_status=None)

        self._outbox_repository.append(build_outbox_record(
            ImprovementCandidateCreated(candidate_id=saved.candidate_id, candidate_type=saved.candidate_type.value, occurred_at=now),
            "improvement.candidate.created.v1", aggregate_id=str(saved.candidate_id), occurred_at=now,
            correlation_id=to_correlation_id(command.correlation_id),
        ))
        self._audit_recorder.record(
            action="create_candidate", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        self._telemetry.record_candidate(saved.candidate_type.value, saved.status.value, saved.risk_level.value)
        return candidate_to_view(saved)

    def record_benchmark(self, command: RecordCandidateBenchmarkCommand) -> ImprovementCandidateView:
        """04-use-cases UC-EI-004 step 4: "候选必须进入 benchmark，不允许直接发布." A failed
        benchmark immediately rejects the candidate.
        """
        candidate = self._require_candidate(command.candidate_id)
        now = self._clock.now()
        if candidate.status == CandidateStatus.DRAFT:
            candidate = candidate.start_benchmarking(now)
            self._candidate_repository.save(candidate, expected_status=CandidateStatus.DRAFT)

        original_status = candidate.status
        candidate = candidate.record_benchmark_result(command.passed, now)
        if not command.passed:
            candidate = candidate.reject(now)
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="record_candidate_benchmark", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="PASSED" if command.passed else "REJECTED", correlation_id=command.correlation_id,
        )
        return candidate_to_view(saved)

    def request_approval(self, command: RequestCandidateApprovalCommand) -> ImprovementCandidateView:
        """02-business-invariants INV-EI-002: candidate promotion requires 06 governance
        approval before Canary. 08-transaction-and-outbox: "Candidate 状态进入
        PENDING_APPROVAL 时，必须记录 06 approval request 引用."
        """
        candidate = self._require_candidate(command.candidate_id)
        original_status = candidate.status
        now = self._clock.now()
        candidate = candidate.request_approval(now)
        approval_ref = self._policy_approval_port.request_approval(
            candidate.candidate_id, candidate.target_component, candidate.risk_level.value, command.actor,
        )
        candidate = candidate.bind_approval_request(approval_ref.approval_request_id, now)
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="request_candidate_approval", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return candidate_to_view(saved)

    def approve(self, command: ApproveCandidateCommand) -> ImprovementCandidateView:
        """11-security: "自动生成的 candidate 不能自我审批" — enforced inside
        ImprovementCandidate.approve() itself (SelfApprovalNotAllowedException).
        """
        candidate = self._require_candidate(command.candidate_id)
        original_status = candidate.status
        now = self._clock.now()
        candidate = candidate.approve(command.approved_by, now)
        saved = self._candidate_repository.save(candidate, expected_status=original_status)

        self._outbox_repository.append(build_outbox_record(
            ImprovementCandidateApproved(
                candidate_id=saved.candidate_id, approval_request_id=saved.approval_request_id or "", occurred_at=now,
            ),
            "improvement.candidate.approved.v1", aggregate_id=str(saved.candidate_id), occurred_at=now,
            correlation_id=to_correlation_id(command.correlation_id),
        ))
        self._audit_recorder.record(
            action="approve_candidate", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        self._telemetry.record_candidate(saved.candidate_type.value, saved.status.value, saved.risk_level.value)
        return candidate_to_view(saved)

    def reject(self, command: RejectCandidateCommand) -> ImprovementCandidateView:
        candidate = self._require_candidate(command.candidate_id)
        original_status = candidate.status
        candidate = candidate.reject(self._clock.now())
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="reject_candidate", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
            detail=f'{{"reason": {command.reason!r}}}',
        )
        return candidate_to_view(saved)

    def promote(self, command: PromoteCandidateCommand) -> ImprovementCandidateView:
        """02-business-invariants INV-EI-002: promotion requires benchmark + release
        gate + 06 approval + Canary — the Canary sub-state must already be SUCCEEDED.
        """
        candidate = self._require_candidate(command.candidate_id)
        if candidate.canary_status is None or candidate.canary_status.value != "SUCCEEDED":
            raise ValueError(f"candidate {command.candidate_id} cannot promote before its canary has SUCCEEDED")
        original_status = candidate.status
        candidate = candidate.promote(command.promoted_version, self._clock.now())
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="promote_candidate", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        self._telemetry.record_candidate(saved.candidate_type.value, saved.status.value, saved.risk_level.value)
        return candidate_to_view(saved)

    def find_candidate(self, candidate_id: CandidateId) -> ImprovementCandidateView:
        return candidate_to_view(self._require_candidate(candidate_id))

    def _require_candidate(self, candidate_id: CandidateId) -> ImprovementCandidate:
        candidate = self._candidate_repository.find_by_id(candidate_id)
        if candidate is None:
            raise CandidateNotFoundException(candidate_id)
        return candidate


def _request_payload(command: CreateImprovementCandidateCommand) -> dict:
    return {
        "candidateType": command.candidate_type.value, "sourceRunId": str(command.source_run_id),
        "sourceFailureClusterId": command.source_failure_cluster_id, "targetComponent": command.target_component,
        "proposedChange": command.proposed_change, "riskLevel": command.risk_level.value, "createdBy": command.created_by,
    }


def candidate_to_view(candidate: ImprovementCandidate) -> ImprovementCandidateView:
    return ImprovementCandidateView(
        candidate_id=candidate.candidate_id, candidate_type=candidate.candidate_type, source_run_id=candidate.source_run_id,
        target_component=candidate.target_component, risk_level=candidate.risk_level, status=candidate.status,
        created_by=candidate.created_by, approved_by=candidate.approved_by, approval_request_id=candidate.approval_request_id,
        canary_status=candidate.canary_status, promoted_version=candidate.promoted_version, created_at=candidate.created_at,
        updated_at=candidate.updated_at,
    )


def candidate_view_to_dict(view: ImprovementCandidateView) -> dict:
    return {
        "candidate_id": str(view.candidate_id.value), "candidate_type": view.candidate_type.value,
        "source_run_id": str(view.source_run_id.value), "target_component": view.target_component,
        "risk_level": view.risk_level.value, "status": view.status.value, "created_by": view.created_by,
        "approved_by": view.approved_by, "approval_request_id": view.approval_request_id,
        "canary_status": view.canary_status.value if view.canary_status else None,
        "promoted_version": view.promoted_version, "created_at": view.created_at.isoformat(),
        "updated_at": view.updated_at.isoformat(),
    }


def candidate_view_from_dict(data: dict) -> ImprovementCandidateView:
    return ImprovementCandidateView(
        candidate_id=CandidateId(uuid.UUID(data["candidate_id"])), candidate_type=CandidateType[data["candidate_type"]],
        source_run_id=RunId(uuid.UUID(data["source_run_id"])), target_component=data["target_component"],
        risk_level=RiskLevel[data["risk_level"]], status=CandidateStatus[data["status"]], created_by=data["created_by"],
        approved_by=data["approved_by"], approval_request_id=data["approval_request_id"],
        canary_status=CanaryStatus[data["canary_status"]] if data["canary_status"] else None,
        promoted_version=data["promoted_version"], created_at=datetime.fromisoformat(data["created_at"]),
        updated_at=datetime.fromisoformat(data["updated_at"]),
    )
