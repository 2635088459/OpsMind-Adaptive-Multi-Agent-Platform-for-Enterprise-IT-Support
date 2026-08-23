"""13-package-and-class-design §"Application Layer": ``approve_tool_request.py``.
04-use-cases UC-TG-003 steps 4-5: consumes the approval decision for a
WAITING_APPROVAL ToolRequest.

- ``record_approval_decision`` applies an already-validated decision — the
  core state transition, callable directly (tests, admin override).
- ``consume_approval_decision`` (SPEC-TG-009) is the real event-consumer
  entrypoint: ``approval.granted.v1``/``approval.denied.v1`` off the broker,
  reached via ``api.event_routes`` (a manual/ops-trigger HTTP seam until a real
  RabbitMQ consumer exists — mirrors memory-knowledge-service's own
  ``interfaces/event/router.py`` precedent). 09-concurrency-and-idempotency
  §"Approval Event Idempotency": event-id dedup via ``ProcessedEventRepository``,
  an already-resolved ToolRequest is a silent skip (not an error), and the
  event's own ``approvalRequestId`` must match the ToolRequest's stored
  linkage or the decision is rejected with a security audit record.

SPEC-TG-008 10-failure-handling §"Policy / Approval Failure": a denial
publishes a final ``tool.completed.v1`` with status APPROVAL_DENIED — built via
``application.outbox_events.build_denied_completed_event``, which needs no
``ToolExecution``/``ToolResultEnvelope`` (01-domain-model §"Aggregate Rules":
"A ToolRequest may have no ToolExecution ... after policy denial" applies
identically to approval denial — no execution ever exists for a request denied
before reaching QUEUED).
"""

from __future__ import annotations

import uuid

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import ConsumeApprovalDecisionCommand, RecordApprovalDecisionCommand
from tool_gateway.application.exceptions import ApprovalLinkageMismatchException, ToolRequestNotFoundException
from tool_gateway.application.outbox_events import build_denied_completed_event
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.application.views import ToolRequestView
from tool_gateway.domain.enums import ToolRequestStatus
from tool_gateway.domain.ids import ToolRequestId
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, OutboxRepository, ProcessedEventRepository, ToolRequestRepository

_CONSUMER_NAME = "approval-decision-consumer"


class ApproveToolRequestService:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, outbox_repository: OutboxRepository,
        processed_event_repository: ProcessedEventRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
        telemetry: ToolGatewayTelemetry,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._outbox_repository = outbox_repository
        self._processed_event_repository = processed_event_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)
        self._telemetry = telemetry

    def record_approval_decision(self, command: RecordApprovalDecisionCommand) -> ToolRequestView:
        tool_request_id = ToolRequestId(uuid.UUID(command.tool_request_id))
        tool_request = self._tool_request_repository.find_by_id(tool_request_id)
        if tool_request is None:
            raise ToolRequestNotFoundException(command.tool_request_id)

        expected_status = tool_request.status
        now = self._clock.now()
        # 12-observability §"Metrics": "tool_approval_wait_seconds{capability,
        # riskLevel}" — the WAITING_APPROVAL entry timestamp is this row's own
        # updated_at (set by evaluate_tool_request.require_approval()); no
        # dedicated "requested_at" column exists to read instead.
        wait_seconds = (now - tool_request.updated_at).total_seconds()
        risk_level_name = tool_request.risk_snapshot.risk_level.name if tool_request.risk_snapshot else "UNKNOWN"

        if command.approved:
            tool_request = tool_request.receive_approval_granted(now)
            tool_request = tool_request.enqueue(now)
            saved = self._tool_request_repository.save(tool_request, expected_status=expected_status)
            self._audit_recorder.record(
                action="approval_granted", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
                outcome="QUEUED", actor_id=command.decided_by, correlation_id=command.correlation_id,
            )
            self._telemetry.record_approval_wait(wait_seconds, saved.capability_name, risk_level_name)
            return ToolRequestView.from_domain(saved)

        tool_request = tool_request.receive_approval_denied(command.denial_reason or "approval denied", now)
        saved = self._tool_request_repository.save(tool_request, expected_status=expected_status)
        self._audit_recorder.record(
            action="approval_denied", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
            outcome="APPROVAL_DENIED", actor_id=command.decided_by, correlation_id=command.correlation_id,
            detail=saved.denial_reason,
        )
        self._outbox_repository.append(build_denied_completed_event(saved, "APPROVAL_DENIED", command.correlation_id, now))
        self._telemetry.record_approval_wait(wait_seconds, saved.capability_name, risk_level_name)
        self._telemetry.record_request_completed("APPROVAL_DENIED")
        return ToolRequestView.from_domain(saved)

    def consume_approval_decision(self, command: ConsumeApprovalDecisionCommand) -> ToolRequestView:
        tool_request_id = ToolRequestId(uuid.UUID(command.tool_request_id))

        # 08-transaction-and-outbox §"Processed Events" / 09-concurrency-and-
        # idempotency: dedup by eventId + consumerName before applying anything.
        if self._processed_event_repository.is_processed(command.event_id, _CONSUMER_NAME):
            tool_request = self._tool_request_repository.find_by_id(tool_request_id)
            if tool_request is None:
                raise ToolRequestNotFoundException(command.tool_request_id)
            return ToolRequestView.from_domain(tool_request)

        tool_request = self._tool_request_repository.find_by_id(tool_request_id)
        if tool_request is None:
            raise ToolRequestNotFoundException(command.tool_request_id)

        # 09-concurrency-and-idempotency §"Approval Event Idempotency": "If
        # ToolRequest is already QUEUED, EXECUTING, or final, skip." A
        # WAITING_APPROVAL check covers all of those in one comparison — every
        # other status (QUEUED/EXECUTING/COMPLETED/FAILED/... ) is downstream
        # of WAITING_APPROVAL and must not replay the decision.
        if tool_request.status is not ToolRequestStatus.WAITING_APPROVAL:
            self._processed_event_repository.mark_processed(command.event_id, _CONSUMER_NAME, self._clock.now())
            return ToolRequestView.from_domain(tool_request)

        # 09-concurrency-and-idempotency §"Approval Event Idempotency": "If
        # approval linkage does not match, write security audit and reject."
        stored_approval_request_id = str(tool_request.approval_ref.approval_request_id) if tool_request.approval_ref else None
        if stored_approval_request_id != command.approval_request_id:
            self._audit_recorder.record(
                action="approval_linkage_mismatch", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
                outcome="REJECTED", actor_id=command.decided_by, correlation_id=command.correlation_id,
                detail=f"event approvalRequestId={command.approval_request_id} != stored {stored_approval_request_id}",
            )
            self._processed_event_repository.mark_processed(command.event_id, _CONSUMER_NAME, self._clock.now())
            raise ApprovalLinkageMismatchException(command.tool_request_id, command.approval_request_id)

        result = self.record_approval_decision(RecordApprovalDecisionCommand(
            tool_request_id=command.tool_request_id, approved=command.approved, decided_by=command.decided_by,
            correlation_id=command.correlation_id, denial_reason=command.denial_reason,
        ))
        self._processed_event_repository.mark_processed(command.event_id, _CONSUMER_NAME, self._clock.now())
        return result
