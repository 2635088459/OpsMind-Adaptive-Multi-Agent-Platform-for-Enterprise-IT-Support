"""13-package-and-class-design's own ``api/`` tree lists only
``runtime_routes.py``/``connector_admin_routes.py``/``result_routes.py``/
``schemas.py`` — this module is a SPEC-TG-009 extension, mirroring memory-
knowledge-service's own ``interfaces/event/router.py`` precedent exactly: a
manual/ops-trigger HTTP seam for the events 06-event-contracts names as
consumed (``approval.granted.v1``/``approval.denied.v1``/
``policy.rule.changed.v1``/``workflow.cancelled.v1`` — SPEC-TG-022 added the
last one) until a real RabbitMQ consumer exists (see
``adapters.events.rabbitmq_consumer`` module docstring — that stub is what a
future spec replaces; this endpoint is what it would call into instead of a
client hitting it directly).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from tool_gateway.api.schemas import (
    ApprovalDeniedEventRequest,
    ApprovalGrantedEventRequest,
    EventIngestResponse,
    PolicyRuleChangedEventRequest,
    ToolRequestResponse,
    WorkflowCancelledEventRequest,
    WorkflowCancelledIngestResponse,
)
from tool_gateway.application.commands import (
    ConsumeApprovalDecisionCommand,
    ConsumePolicyRuleChangedCommand,
    ConsumeWorkflowCancelledCommand,
)
from tool_gateway.application.ports_in import ApproveToolRequestUseCase, PolicyRuleChangeConsumerUseCase, WorkflowCancelledConsumerUseCase
from tool_gateway.application.views import ToolRequestView
from tool_gateway.container import (
    get_approve_tool_request_port,
    get_policy_rule_change_consumer_port,
    get_workflow_cancelled_consumer_port,
)

router = APIRouter(prefix="/internal/tool-gateway/v1/events", tags=["events"])


def _to_response(view: ToolRequestView) -> ToolRequestResponse:
    return ToolRequestResponse(
        tool_request_id=view.tool_request_id, status=view.status, capability_name=view.capability_name,
        tool_name=view.tool_name, requested_by_type=view.requested_by_type, requested_by_id=view.requested_by_id,
        reason=view.reason, requires_approval=view.requires_approval, approval_request_id=view.approval_request_id,
        denial_reason=view.denial_reason, result_envelope_id=view.result_envelope_id,
        created_at=view.created_at, updated_at=view.updated_at,
    )


@router.post("/approval-granted", response_model=ToolRequestResponse)
def ingest_approval_granted(
    request: ApprovalGrantedEventRequest, port: ApproveToolRequestUseCase = Depends(get_approve_tool_request_port),
) -> ToolRequestResponse:
    """06-event-contracts §"approval.granted.v1"."""

    return _to_response(port.consume_approval_decision(ConsumeApprovalDecisionCommand(
        event_id=request.event_id, tool_request_id=request.tool_request_id, approval_request_id=request.approval_request_id,
        approved=True, decided_by=request.approved_by, correlation_id=request.correlation_id,
    )))


@router.post("/approval-denied", response_model=ToolRequestResponse)
def ingest_approval_denied(
    request: ApprovalDeniedEventRequest, port: ApproveToolRequestUseCase = Depends(get_approve_tool_request_port),
) -> ToolRequestResponse:
    """06-event-contracts §"approval.denied.v1": "Gateway must publish
    tool.completed.v1 with status DENIED so Runtime can resume from waiting
    state" — handled inside ``consume_approval_decision`` ->
    ``record_approval_decision``.
    """

    return _to_response(port.consume_approval_decision(ConsumeApprovalDecisionCommand(
        event_id=request.event_id, tool_request_id=request.tool_request_id, approval_request_id=request.approval_request_id,
        approved=False, decided_by=request.denied_by, correlation_id=request.correlation_id, denial_reason=request.denial_reason,
    )))


@router.post("/policy-rule-changed", response_model=EventIngestResponse)
def ingest_policy_rule_changed(
    request: PolicyRuleChangedEventRequest, port: PolicyRuleChangeConsumerUseCase = Depends(get_policy_rule_change_consumer_port),
) -> EventIngestResponse:
    """06-event-contracts §"policy.rule.changed.v1"."""

    applied = port.consume_policy_rule_changed(ConsumePolicyRuleChangedCommand(
        event_id=request.event_id, rule_id=request.rule_id, correlation_id=request.correlation_id,
    ))
    return EventIngestResponse(event_id=request.event_id, applied=applied)


@router.post("/workflow-cancelled", response_model=WorkflowCancelledIngestResponse)
def ingest_workflow_cancelled(
    request: WorkflowCancelledEventRequest, port: WorkflowCancelledConsumerUseCase = Depends(get_workflow_cancelled_consumer_port),
) -> WorkflowCancelledIngestResponse:
    """SPEC-TG-022 06-event-contracts §"workflow.cancelled.v1": "when Runtime
    workflow is cancelled, Gateway attempts to cancel associated pending/
    running Tool Requests."
    """

    cancelled_count = port.consume_workflow_cancelled(ConsumeWorkflowCancelledCommand(
        event_id=request.event_id, workflow_instance_id=request.workflow_instance_id, correlation_id=request.correlation_id,
    ))
    return WorkflowCancelledIngestResponse(event_id=request.event_id, cancelled_count=cancelled_count)
