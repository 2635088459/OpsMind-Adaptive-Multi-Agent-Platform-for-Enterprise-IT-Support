"""13-package-and-class-design §"api/result_routes.py". 05-api-contracts
§"Result API": ``GET /tool-results/{resultEnvelopeId}`` returns the redacted
result — INV-TG-007 "Raw output can be read only through controlled storage
references" — this endpoint returns ``raw_output_ref`` (a reference), never raw
content itself.

``GET /tool-results/{resultEnvelopeId}/raw`` ("Controlled raw output access.
Requires privileged RBAC, audit reason, and policy check") is SPEC-TG-020's own
addition — see ``application.execute_tool_request.ExecuteToolRequestService.
find_raw_output``/``application.exceptions.RawOutputForbiddenException`` for
the HUMAN_OPERATOR-only + mandatory-reason gate it enforces. Caller identity/
reason travel as headers (mirroring ``X-Correlation-Id``'s own existing
convention in ``api.errors``) rather than a request body, since this is a GET.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Header

from tool_gateway.api.schemas import RawOutputResponse, ToolResultResponse
from tool_gateway.application.ports_in import ToolResultQueryUseCase
from tool_gateway.application.views import RawOutputView, ToolResultView
from tool_gateway.container import get_tool_result_query_port

router = APIRouter(prefix="/internal/tool-gateway/v1", tags=["tool-results"])


def _to_response(view: ToolResultView) -> ToolResultResponse:
    return ToolResultResponse(
        result_envelope_id=view.result_envelope_id, execution_id=view.execution_id, status=view.status,
        summary=view.summary, structured_output=view.structured_output, raw_output_ref=view.raw_output_ref,
        redaction_status=view.redaction_status, evidence_refs=view.evidence_refs, error_code=view.error_code,
        retryable=view.retryable,
    )


def _to_raw_output_response(view: RawOutputView) -> RawOutputResponse:
    return RawOutputResponse(result_envelope_id=view.result_envelope_id, raw_output=view.raw_output)


@router.get("/tool-results/{result_envelope_id}", response_model=ToolResultResponse)
def find_result(result_envelope_id: str, port: ToolResultQueryUseCase = Depends(get_tool_result_query_port)) -> ToolResultResponse:
    return _to_response(port.find_result(result_envelope_id))


@router.get("/tool-results/{result_envelope_id}/raw", response_model=RawOutputResponse)
def find_raw_output(
    result_envelope_id: str,
    reason: str,
    x_requested_by_type: str = Header(description="Must be HUMAN_OPERATOR — 11-security §\"Agent Isolation\"."),
    x_requested_by_id: str = Header(),
    x_correlation_id: str = Header(default=""),
    port: ToolResultQueryUseCase = Depends(get_tool_result_query_port),
) -> RawOutputResponse:
    return _to_raw_output_response(port.find_raw_output(
        result_envelope_id, x_requested_by_type, x_requested_by_id, reason, x_correlation_id,
    ))
