"""13-package-and-class-design §"api/runtime_routes.py": the Runtime-facing
surface (04-use-cases UC-TG-001/UC-TG-003/UC-TG-006). Depends only on
``ports_in`` Protocols, handed to it by ``tool_gateway.container`` via
``Depends()`` — never touches a repository or adapter directly.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from tool_gateway.api.schemas import ApprovalDecisionRequest, CancelToolRequestRequest, SubmitToolRequestRequest, ToolRequestResponse
from tool_gateway.application.commands import (
    CancelToolRequestCommand,
    CreateToolRequestCommand,
    EvaluateToolRequestCommand,
    RecordApprovalDecisionCommand,
)
from tool_gateway.application.ports_in import (
    ApproveToolRequestUseCase,
    CancelToolRequestUseCase,
    CreateToolRequestUseCase,
    EvaluateToolRequestUseCase,
    ToolRequestQueryUseCase,
)
from tool_gateway.application.views import ToolRequestView
from tool_gateway.container import (
    get_approve_tool_request_port,
    get_cancel_tool_request_port,
    get_create_tool_request_port,
    get_evaluate_tool_request_port,
    get_tool_request_query_port,
)

router = APIRouter(prefix="/internal/tool-gateway/v1", tags=["tool-requests"])


def _to_response(view: ToolRequestView) -> ToolRequestResponse:
    return ToolRequestResponse(
        tool_request_id=view.tool_request_id, status=view.status, capability_name=view.capability_name,
        tool_name=view.tool_name, requested_by_type=view.requested_by_type, requested_by_id=view.requested_by_id,
        reason=view.reason, requires_approval=view.requires_approval, approval_request_id=view.approval_request_id,
        denial_reason=view.denial_reason, result_envelope_id=view.result_envelope_id,
        created_at=view.created_at, updated_at=view.updated_at,
    )


@router.post("/tool-requests", response_model=ToolRequestResponse)
def submit_tool_request(
    request: SubmitToolRequestRequest,
    create_port: CreateToolRequestUseCase = Depends(get_create_tool_request_port),
    evaluate_port: EvaluateToolRequestUseCase = Depends(get_evaluate_tool_request_port),
) -> ToolRequestResponse:
    """05-api-contracts / 04-use-cases UC-TG-001 + UC-TG-002/UC-TG-003 steps
    1-3: create and evaluate are chained synchronously here — INV-TG-001 makes
    this the only entry point Agent Runtime may use to reach tool execution.
    """

    created = create_port.create_tool_request(CreateToolRequestCommand(
        idempotency_key=request.idempotency_key, requested_by_type=request.requested_by_type,
        requested_by_id=request.requested_by_id, capability_name=request.capability_name,
        input_payload=request.input_payload, reason=request.reason, correlation_id=request.correlation_id,
        ticket_id=request.ticket_id, ticket_cycle_id=request.ticket_cycle_id,
        workflow_instance_id=request.workflow_instance_id, agent_task_id=request.agent_task_id, tool_name=request.tool_name,
    ))
    if created.status != "VALIDATING":
        # REJECTED (or an idempotent replay already past VALIDATING) — nothing
        # further to evaluate.
        return _to_response(created)

    evaluated = evaluate_port.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=request.correlation_id,
    ))
    return _to_response(evaluated)


@router.post("/tool-requests/{tool_request_id}/approval-decisions", response_model=ToolRequestResponse)
def record_approval_decision(
    tool_request_id: str, request: ApprovalDecisionRequest,
    port: ApproveToolRequestUseCase = Depends(get_approve_tool_request_port),
) -> ToolRequestResponse:
    """04-use-cases UC-TG-003 steps 4-5. Directly callable for now — see
    ``application.approve_tool_request`` module docstring for why real
    ``approval.granted.v1``/``approval.denied.v1`` consumption is phase-02/06
    scope.
    """

    return _to_response(port.record_approval_decision(RecordApprovalDecisionCommand(
        tool_request_id=tool_request_id, approved=request.approved, decided_by=request.decided_by,
        correlation_id=request.correlation_id, denial_reason=request.denial_reason,
    )))


@router.post("/tool-requests/{tool_request_id}/cancel", response_model=ToolRequestResponse)
def cancel_tool_request(
    tool_request_id: str, request: CancelToolRequestRequest,
    port: CancelToolRequestUseCase = Depends(get_cancel_tool_request_port),
) -> ToolRequestResponse:
    """04-use-cases UC-TG-006."""

    return _to_response(port.cancel_tool_request(CancelToolRequestCommand(
        tool_request_id=tool_request_id, idempotency_key=request.idempotency_key, requested_by=request.requested_by,
        reason=request.reason, correlation_id=request.correlation_id,
    )))


@router.get("/tool-requests/{tool_request_id}", response_model=ToolRequestResponse)
def find_tool_request(
    tool_request_id: str, port: ToolRequestQueryUseCase = Depends(get_tool_request_query_port),
) -> ToolRequestResponse:
    return _to_response(port.find_tool_request(tool_request_id))
