"""13-package-and-class-design §"应用层": ManageCanaryService, the sole
implementation of ManageCanaryUseCase. 04-use-cases UC-EI-005 steps 3-5: "Canary
manager 创建小流量 rollout plan" / "线上抽样评估通过后扩大流量" / "失败时发布 rollback
requested."
"""

from __future__ import annotations

from evaluationimprovement.application.commands import (
    AdvanceCanaryCommand,
    CompleteCanaryRollbackCommand,
    PauseCanaryCommand,
    RequestCanaryRollbackCommand,
    StartCanaryCommand,
)
from evaluationimprovement.application.exceptions import CandidateNotFoundException
from evaluationimprovement.application.outbox_codec import build_outbox_record, to_correlation_id
from evaluationimprovement.application.ports_out import (
    AuditRecordRepository,
    ClockPort,
    CommandIdempotencyRepository,
    ImprovementCandidateRepository,
    OutboxRepository,
)
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.services.create_improvement_candidate import (
    candidate_to_view,
    candidate_view_from_dict,
    candidate_view_to_dict,
)
from evaluationimprovement.application.services.idempotency import CommandIdempotencyGuard
from evaluationimprovement.application.telemetry import EvaluationTelemetry
from evaluationimprovement.application.views import ImprovementCandidateView
from evaluationimprovement.domain.enums import CanaryStatus
from evaluationimprovement.domain.events import ImprovementRollbackRequested
from evaluationimprovement.domain.ids import CandidateId
from evaluationimprovement.domain.improvement_candidate import ImprovementCandidate
from evaluationimprovement.domain.values import CanaryPlan, CanaryStage


class ManageCanaryService:
    def __init__(
        self, candidate_repository: ImprovementCandidateRepository, command_idempotency_repository: CommandIdempotencyRepository,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
        telemetry: EvaluationTelemetry,
    ) -> None:
        self._candidate_repository = candidate_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._telemetry = telemetry
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def start_canary(self, command: StartCanaryCommand) -> ImprovementCandidateView:
        return self._idempotency_guard.run(
            command_type="start_canary", target_id=str(command.candidate_id), idempotency_key=command.idempotency_key,
            request_payload={"candidateId": str(command.candidate_id), "planVersion": command.plan_version},
            execute=lambda: self._do_start_canary(command), to_dict=candidate_view_to_dict, from_dict=candidate_view_from_dict,
        )

    def _do_start_canary(self, command: StartCanaryCommand) -> ImprovementCandidateView:
        candidate = self._require_candidate(command.candidate_id)
        original_status = candidate.status
        now = self._clock.now()
        plan = CanaryPlan(
            plan_version=command.plan_version,
            stages=tuple(
                CanaryStage(s.traffic_percent, s.min_duration_minutes, s.rollback_error_rate_threshold) for s in command.stages
            ),
        )
        # 02-business-invariants INV-EI-002: start_canary() itself refuses without a
        # bound 06 approval_request_id. Immediately activated (PLANNED -> ACTIVE) —
        # a plan that is not yet serving any traffic is not meaningfully "started."
        candidate = candidate.start_canary(plan, now).canary_activate(now)
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="start_canary", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return candidate_to_view(saved)

    def advance(self, command: AdvanceCanaryCommand) -> ImprovementCandidateView:
        return self._idempotency_guard.run(
            command_type="advance_canary", target_id=str(command.candidate_id), idempotency_key=command.idempotency_key,
            request_payload={"candidateId": str(command.candidate_id)}, execute=lambda: self._do_advance(command),
            to_dict=candidate_view_to_dict, from_dict=candidate_view_from_dict,
        )

    def _do_advance(self, command: AdvanceCanaryCommand) -> ImprovementCandidateView:
        """02-business-invariants INV-EI-010: "Canary 扩流必须有明确阈值、时间窗和自动回滚
        条件" — this method only ever advances one declared stage at a time (ACTIVE ->
        EXPANDING -> SUCCEEDED); it never skips a stage. The caller (an admin, or a
        future SPEC-EI-028 online-sample-evaluation trigger) is responsible for having
        already verified the current stage's own threshold/time-window before calling.
        """
        candidate = self._require_candidate(command.candidate_id)
        original_status = candidate.status
        now = self._clock.now()
        if candidate.canary_status == CanaryStatus.ACTIVE:
            candidate = candidate.canary_expand(now)
        elif candidate.canary_status == CanaryStatus.EXPANDING:
            candidate = candidate.canary_succeed(now)
        elif candidate.canary_status == CanaryStatus.PAUSED:
            candidate = candidate.canary_activate(now)
        else:
            raise ValueError(f"candidate {command.candidate_id} canary is {candidate.canary_status} and cannot advance")
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="advance_canary", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return candidate_to_view(saved)

    def pause(self, command: PauseCanaryCommand) -> ImprovementCandidateView:
        return self._idempotency_guard.run(
            command_type="pause_canary", target_id=str(command.candidate_id), idempotency_key=command.idempotency_key,
            request_payload={"candidateId": str(command.candidate_id), "reason": command.reason},
            execute=lambda: self._do_pause(command), to_dict=candidate_view_to_dict, from_dict=candidate_view_from_dict,
        )

    def _do_pause(self, command: PauseCanaryCommand) -> ImprovementCandidateView:
        candidate = self._require_candidate(command.candidate_id)
        original_status = candidate.status
        candidate = candidate.canary_pause(self._clock.now())
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="pause_canary", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
            detail=f'{{"reason": {command.reason!r}}}',
        )
        return candidate_to_view(saved)

    def request_rollback(self, command: RequestCanaryRollbackCommand) -> ImprovementCandidateView:
        return self._idempotency_guard.run(
            command_type="request_canary_rollback", target_id=str(command.candidate_id),
            idempotency_key=command.idempotency_key,
            request_payload={"candidateId": str(command.candidate_id), "reason": command.reason},
            execute=lambda: self._do_request_rollback(command), to_dict=candidate_view_to_dict, from_dict=candidate_view_from_dict,
        )

    def _do_request_rollback(self, command: RequestCanaryRollbackCommand) -> ImprovementCandidateView:
        candidate = self._require_candidate(command.candidate_id)
        original_status = candidate.status
        now = self._clock.now()
        if candidate.canary_status in (CanaryStatus.ACTIVE, CanaryStatus.EXPANDING, CanaryStatus.PAUSED):
            candidate = candidate.canary_fail(now)
        if candidate.canary_status == CanaryStatus.FAILED:
            candidate = candidate.canary_request_rollback(now)
        else:
            raise ValueError(f"candidate {command.candidate_id} canary is {candidate.canary_status} and cannot request rollback")
        saved = self._candidate_repository.save(candidate, expected_status=original_status)

        self._outbox_repository.append(build_outbox_record(
            ImprovementRollbackRequested(candidate_id=saved.candidate_id, reason=command.reason, occurred_at=now),
            "improvement.rollback.requested.v1", aggregate_id=str(saved.candidate_id), occurred_at=now,
            correlation_id=to_correlation_id(command.correlation_id),
        ))
        self._audit_recorder.record(
            action="request_canary_rollback", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
            detail=f'{{"reason": {command.reason!r}}}',
        )
        self._telemetry.record_canary_rollback(command.reason)
        return candidate_to_view(saved)

    def complete_rollback(self, command: CompleteCanaryRollbackCommand) -> ImprovementCandidateView:
        """10-failure-handling §"Candidate Rollback" step 3-4: "Runtime/Config owner
        执行回滚" then "07 通过后续 trace 验证回滚效果" — this method records that the
        rollback the improvement owner already executed has completed.
        """
        candidate = self._require_candidate(command.candidate_id)
        if candidate.canary_status != CanaryStatus.ROLLBACK_REQUESTED:
            raise ValueError(f"candidate {command.candidate_id} canary is {candidate.canary_status}, expected ROLLBACK_REQUESTED")
        original_status = candidate.status
        candidate = candidate.rollback(self._clock.now())
        saved = self._candidate_repository.save(candidate, expected_status=original_status)
        self._audit_recorder.record(
            action="complete_canary_rollback", resource_type="IMPROVEMENT_CANDIDATE", resource_id=str(saved.candidate_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return candidate_to_view(saved)

    def _require_candidate(self, candidate_id: CandidateId) -> ImprovementCandidate:
        candidate = self._candidate_repository.find_by_id(candidate_id)
        if candidate is None:
            raise CandidateNotFoundException(candidate_id)
        return candidate

