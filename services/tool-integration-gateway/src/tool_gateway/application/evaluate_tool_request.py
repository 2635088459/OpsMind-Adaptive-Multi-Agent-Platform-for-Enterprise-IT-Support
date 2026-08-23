"""13-package-and-class-design §"Application Layer": ``evaluate_tool_request.py``.
04-use-cases UC-TG-002/UC-TG-003: computes the risk decision for a VALIDATING
ToolRequest and routes it three ways:

- SPEC-TG-007: a hard policy deny (``RiskDecisionRef.denied``) moves straight to
  POLICY_DENIED and publishes a final ``tool.completed.v1`` — 10-failure-handling
  §"Policy / Approval Failure".
- SPEC-TG-008: ``requires_approval`` parks at WAITING_APPROVAL and publishes
  ``tool.approval.required.v1`` so 06-policy-approval-governance can create or
  link an approval request (06-event-contracts). Steps 4-5 of UC-TG-003 (the
  actual decision) are ``approve_tool_request``'s job.
- UC-TG-002: the low-risk/no-approval-required path auto-approves straight to
  QUEUED.
"""

from __future__ import annotations

import uuid

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import EvaluateToolRequestCommand
from tool_gateway.application.exceptions import ToolRequestNotFoundException
from tool_gateway.application.outbox_events import build_approval_required_event, build_denied_completed_event
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.application.views import ToolRequestView
from tool_gateway.domain.ids import ToolRequestId
from tool_gateway.ports.approval_port import ApprovalPort
from tool_gateway.ports.policy_port import PolicyPort
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, OutboxRepository, ToolRequestRepository


class EvaluateToolRequestService:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, policy_port: PolicyPort, approval_port: ApprovalPort,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
        telemetry: ToolGatewayTelemetry,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._policy_port = policy_port
        self._approval_port = approval_port
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)
        self._telemetry = telemetry

    def evaluate_tool_request(self, command: EvaluateToolRequestCommand) -> ToolRequestView:
        tool_request_id = ToolRequestId(uuid.UUID(command.tool_request_id))
        tool_request = self._tool_request_repository.find_by_id(tool_request_id)
        if tool_request is None:
            raise ToolRequestNotFoundException(command.tool_request_id)

        expected_status = tool_request.status
        now = self._clock.now()
        tool_request = tool_request.begin_policy_check(now)

        risk_decision = self._policy_port.evaluate(
            tool_request.capability_name, tool_request.requested_by_type.name, tool_request.input_payload,
        )
        self._audit_recorder.record(
            action="policy_decision_received", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
            outcome="DENIED" if risk_decision.denied else risk_decision.risk_level.name,
            actor_id=tool_request.requested_by_id, correlation_id=command.correlation_id,
        )

        if risk_decision.denied:
            tool_request = tool_request.deny_policy(risk_decision.denial_reason or "denied by policy", now)
            saved = self._tool_request_repository.save(tool_request, expected_status=expected_status)
            self._audit_recorder.record(
                action="request_rejected", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
                outcome="POLICY_DENIED", actor_id=tool_request.requested_by_id, correlation_id=command.correlation_id,
                detail=saved.denial_reason,
            )
            self._outbox_repository.append(build_denied_completed_event(saved, "POLICY_DENIED", command.correlation_id, now))
            self._telemetry.record_request_completed("POLICY_DENIED")
            return ToolRequestView.from_domain(saved)

        if risk_decision.requires_approval:
            # INV-TG-005: "Tool Request must enter WAITING_APPROVAL until a valid
            # approval.granted.v1 or approval.denied.v1 is received."
            approval_ref = self._approval_port.request_approval(tool_request_id, risk_decision)
            tool_request = tool_request.require_approval(risk_decision, approval_ref, now)
            saved = self._tool_request_repository.save(tool_request, expected_status=expected_status)
            self._audit_recorder.record(
                action="approval_requested", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
                outcome="WAITING_APPROVAL", actor_id=tool_request.requested_by_id, correlation_id=command.correlation_id,
            )
            # 06-event-contracts §"tool.approval.required.v1": "Published when
            # approval is required, so domain 06 can create or link an approval
            # request."
            self._outbox_repository.append(
                build_approval_required_event(saved, risk_decision.risk_level.name, command.correlation_id, now)
            )
            return ToolRequestView.from_domain(saved)

        # 04-use-cases UC-TG-002 step 2: low-risk/no-approval-required path.
        tool_request = tool_request.auto_approve(risk_decision, now)
        tool_request = tool_request.enqueue(now)
        saved = self._tool_request_repository.save(tool_request, expected_status=expected_status)
        return ToolRequestView.from_domain(saved)
