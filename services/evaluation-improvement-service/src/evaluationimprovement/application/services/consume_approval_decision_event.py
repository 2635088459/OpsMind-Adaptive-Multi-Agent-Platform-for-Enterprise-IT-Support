"""SPEC-EI-032 (policy-approval-release-approval-contract):
ConsumeApprovalDecisionEventService, the sole implementation of
ApprovalDecisionEventConsumerPort. Closes the request/consume loop SPEC-EI-026's own
traceability entry deferred here: `HttpPolicyApprovalAdapter` (SPEC-EI-026) requests
an approval with `sourceDomain="evaluation-improvement"`/`sourceRequestId=candidateId`;
this service consumes 06-policy-approval-governance's own real `approval.granted.v1`/
`approval.denied.v1` events and drives `CreateImprovementCandidateUseCase.approve()`/
`reject()` automatically once 06 decides — the async completion of that same request.

09-concurrency-and-idempotency §"消费事件幂等": dedup on (event_id, CONSUMER_NAME) via
ProcessedEventRepository, mirroring ConsumeCrossDomainEventService's own precedent —
a *redelivered* decision event is a no-op here.

`source_domain` is filtered to `"evaluation-improvement"` before ever looking up a
candidate — 06 publishes one unified approval-decision stream every consumer domain
(02/03/05/07) subscribes to; a decision for another domain's own request is simply
not applied here, the same "not for us" no-op a real topic-filtered subscription
would already give this service for free.

SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling
§"Poison Event": a genuinely late/conflicting decision (the candidate already moved
past PENDING_APPROVAL through some other path, or 06 itself should have refused a
self-approval but didn't) still reaches `approve()`/`reject()`, which raises via the
domain's own state machine — that raise is caught here, recorded to
PoisonEventRepository, and re-raised as PoisonApprovalDecisionEventException, never
marked processed (see that record's own docstring for why: the same event_id must
stay replayable once whatever produced the conflict is fixed upstream).
"""

from __future__ import annotations

import json
import uuid

from evaluationimprovement.application.commands import (
    ApproveCandidateCommand,
    ConsumeApprovalDeniedCommand,
    ConsumeApprovalGrantedCommand,
    RejectCandidateCommand,
)
from evaluationimprovement.application.exceptions import PoisonApprovalDecisionEventException
from evaluationimprovement.application.ports_in import CreateImprovementCandidateUseCase
from evaluationimprovement.application.ports_out import (
    ClockPort,
    ImprovementCandidateRepository,
    PoisonEventRepository,
    ProcessedEventRepository,
)
from evaluationimprovement.application.records import PoisonEventRecord, ProcessedEventRecord
from evaluationimprovement.domain.exceptions import SelfApprovalNotAllowedException
from evaluationimprovement.domain.state_machine import InvalidStateTransitionException

CONSUMER_NAME = "consume_approval_decision_event"
_SOURCE_DOMAIN = "evaluation-improvement"


class ConsumeApprovalDecisionEventService:
    def __init__(
        self, candidate_repository: ImprovementCandidateRepository, create_improvement_candidate_port: CreateImprovementCandidateUseCase,
        processed_event_repository: ProcessedEventRepository, poison_event_repository: PoisonEventRepository, clock: ClockPort,
    ) -> None:
        self._candidate_repository = candidate_repository
        self._create_improvement_candidate_port = create_improvement_candidate_port
        self._processed_event_repository = processed_event_repository
        self._poison_event_repository = poison_event_repository
        self._clock = clock

    def consume_granted(self, command: ConsumeApprovalGrantedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        if command.source_domain != _SOURCE_DOMAIN:
            return False
        candidate = self._candidate_repository.find_by_approval_request_id(command.approval_request_id)
        if candidate is None:
            return False

        self._apply(
            lambda: self._create_improvement_candidate_port.approve(ApproveCandidateCommand(
                candidate_id=candidate.candidate_id, approved_by=command.decided_by, actor="system:approval-decision-consumer",
                correlation_id=command.correlation_id,
            )),
            command.event_id, "approval.granted.v1",
            {"approval_request_id": command.approval_request_id, "source_domain": command.source_domain, "decided_by": command.decided_by},
        )
        return True

    def consume_denied(self, command: ConsumeApprovalDeniedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False
        if command.source_domain != _SOURCE_DOMAIN:
            return False
        candidate = self._candidate_repository.find_by_approval_request_id(command.approval_request_id)
        if candidate is None:
            return False

        self._apply(
            lambda: self._create_improvement_candidate_port.reject(RejectCandidateCommand(
                candidate_id=candidate.candidate_id, reason=f"06 denied approval request {command.approval_request_id}: {command.reason}",
                actor="system:approval-decision-consumer", correlation_id=command.correlation_id,
            )),
            command.event_id, "approval.denied.v1",
            {"approval_request_id": command.approval_request_id, "source_domain": command.source_domain, "reason": command.reason},
        )
        return True

    def _apply(self, action, event_id: str, event_type: str, payload: dict) -> None:  # noqa: ANN001
        try:
            action()
        except (InvalidStateTransitionException, SelfApprovalNotAllowedException) as exc:
            now = self._clock.now()
            self._poison_event_repository.record(PoisonEventRecord(
                id=uuid.uuid4(), event_id=event_id, consumer_name=CONSUMER_NAME, event_type=event_type,
                payload=json.dumps(payload), error_message=str(exc), occurred_at=now, recorded_at=now,
            ))
            raise PoisonApprovalDecisionEventException(event_id, str(exc)) from exc
        self._mark_processed(event_id, event_type)

    def _mark_processed(self, event_id: str, event_type: str) -> None:
        self._processed_event_repository.mark_processed(
            ProcessedEventRecord(event_id=event_id, consumer_name=CONSUMER_NAME, event_type=event_type, processed_at=self._clock.now()),
        )
