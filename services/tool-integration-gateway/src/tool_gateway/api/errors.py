"""05-api-contracts §"Error Model": "Every error response must include
correlationId and auditable error code." Maps application/domain exceptions to
a shared JSON error envelope, mirroring memory-knowledge-service's own
``interfaces/errors.py`` (itself mirroring agent-runtime-service's own)
exactly. Not one of the four ``api/`` filenames 13-package-and-class-design's
own tree literally lists — added the same way that tree's own gaps have
already been filled elsewhere in this service (see e.g.
``adapters/approval``'s own module docstring).

Codes match 05-api-contracts §"Error Model" where one is named there
(``VALIDATION_FAILED``, ``CAPABILITY_NOT_FOUND``, ``CONNECTOR_UNAVAILABLE``,
``IDEMPOTENCY_CONFLICT``); codes not named there (the various ``*_NOT_FOUND``
codes, ``INVALID_STATE_TRANSITION``, ``TOOL_REQUEST_STATE_CONFLICT``) are this
spec's own reasonable additions, the same latitude memory-knowledge-service's
own module docstring takes. ``POLICY_DENIED``/``APPROVAL_REQUIRED``/
``APPROVAL_DENIED``/``EXECUTION_TIMEOUT``/``CONNECTOR_FAILED``/
``PARTIAL_SIDE_EFFECT_UNCERTAIN`` are not wired here — those name ToolRequest/
ToolResultEnvelope *status values* returned in a normal 200 response body
(``status: "POLICY_DENIED"`` etc.), never raised exceptions in this service's
own code. ``RAW_OUTPUT_FORBIDDEN`` (``RawOutputForbiddenException``) was added
by SPEC-TG-020, once the raw-output endpoint itself existed to raise it (see
``api.result_routes``'s own module docstring).
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from tool_gateway.application.exceptions import (
    ApprovalLinkageMismatchException,
    CapabilityNotRegisteredException,
    ConnectorNotFoundException,
    NoActiveExecutionException,
    OutboxRecordNotDeadLetterException,
    OutboxRecordNotFoundException,
    RawOutputForbiddenException,
    ResultEnvelopeNotFoundException,
    ToolExecutionNotFoundException,
    ToolRequestIdempotencyConflictException,
    ToolRequestNotFoundException,
    ToolRequestStatusConflictException,
)
from tool_gateway.domain.errors import (
    ActiveExecutionAlreadyExistsException,
    InvalidApprovalLinkageTransitionException,
    InvalidConnectorHealthTransitionException,
    InvalidToolExecutionTransitionException,
    InvalidToolRequestTransitionException,
    MutationConnectorMissingOperationKeyException,
    ResultEnvelopeMissingSummaryException,
    ToolRequestMissingReasonException,
)

logger = logging.getLogger(__name__)


class ErrorDetail(BaseModel):
    code: str
    message: str
    correlation_id: str = ""
    details: dict[str, Any] = {}


class ErrorResponse(BaseModel):
    error: ErrorDetail


def _body(code: str, message: str, request: Request) -> ErrorResponse:
    correlation_id = request.headers.get("X-Correlation-Id", "")
    return ErrorResponse(error=ErrorDetail(code=code, message=message, correlation_id=correlation_id))


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def handle_validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("VALIDATION_FAILED", "The request is invalid.", request).model_dump())

    @app.exception_handler(ValueError)
    async def handle_value_error(request: Request, exc: ValueError) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("VALIDATION_FAILED", "The request is invalid.", request).model_dump())

    @app.exception_handler(ToolRequestMissingReasonException)
    @app.exception_handler(MutationConnectorMissingOperationKeyException)
    @app.exception_handler(ResultEnvelopeMissingSummaryException)
    async def handle_domain_validation(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("VALIDATION_FAILED", str(exc), request).model_dump())

    @app.exception_handler(ToolRequestNotFoundException)
    async def handle_tool_request_not_found(request: Request, exc: ToolRequestNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("TOOL_REQUEST_NOT_FOUND", "The tool request was not found.", request).model_dump())

    @app.exception_handler(ToolExecutionNotFoundException)
    async def handle_tool_execution_not_found(request: Request, exc: ToolExecutionNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("TOOL_EXECUTION_NOT_FOUND", "The tool execution was not found.", request).model_dump())

    @app.exception_handler(ResultEnvelopeNotFoundException)
    async def handle_result_not_found(request: Request, exc: ResultEnvelopeNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("RESULT_NOT_FOUND", "The tool result was not found.", request).model_dump())

    @app.exception_handler(CapabilityNotRegisteredException)
    async def handle_capability_not_found(request: Request, exc: CapabilityNotRegisteredException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("CAPABILITY_NOT_FOUND", "The capability has no registered, schedulable connector.", request).model_dump())

    @app.exception_handler(ConnectorNotFoundException)
    async def handle_connector_unavailable(request: Request, exc: ConnectorNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, content=_body("CONNECTOR_UNAVAILABLE", "The connector is not available.", request).model_dump())

    @app.exception_handler(NoActiveExecutionException)
    async def handle_no_active_execution(request: Request, exc: NoActiveExecutionException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("NO_ACTIVE_EXECUTION", "The tool request has no active execution attempt.", request).model_dump())

    @app.exception_handler(ToolRequestIdempotencyConflictException)
    async def handle_idempotency_conflict(request: Request, exc: ToolRequestIdempotencyConflictException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("IDEMPOTENCY_CONFLICT", "The idempotency key was already used with a different payload.", request).model_dump())

    @app.exception_handler(ToolRequestStatusConflictException)
    async def handle_status_conflict(request: Request, exc: ToolRequestStatusConflictException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("TOOL_REQUEST_STATE_CONFLICT", "The tool request was modified concurrently.", request).model_dump())

    @app.exception_handler(ActiveExecutionAlreadyExistsException)
    async def handle_active_execution_exists(request: Request, exc: ActiveExecutionAlreadyExistsException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("TOOL_REQUEST_STATE_CONFLICT", "An execution attempt is already active for this tool request.", request).model_dump())

    @app.exception_handler(RawOutputForbiddenException)
    async def handle_raw_output_forbidden(request: Request, exc: RawOutputForbiddenException) -> JSONResponse:
        # 05-api-contracts §"Result API": ``GET /tool-results/{id}/raw``
        # "Requires privileged RBAC, audit reason, and policy check" — 403,
        # matching the same non-conflict "you are not allowed" semantics as
        # APPROVAL_LINKAGE_MISMATCH.
        return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=_body("RAW_OUTPUT_FORBIDDEN", "Raw output access is forbidden.", request).model_dump())

    @app.exception_handler(ApprovalLinkageMismatchException)
    async def handle_approval_linkage_mismatch(request: Request, exc: ApprovalLinkageMismatchException) -> JSONResponse:
        # 09-concurrency-and-idempotency §"Approval Event Idempotency": "write
        # security audit and reject" — 403, not 409: this is not an ordinary
        # concurrent-write conflict, it is a decision that does not belong to
        # this tool request's own approval linkage.
        return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=_body("APPROVAL_LINKAGE_MISMATCH", "The approval decision does not match this tool request's stored approval linkage.", request).model_dump())

    @app.exception_handler(OutboxRecordNotFoundException)
    async def handle_outbox_record_not_found(request: Request, exc: OutboxRecordNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("OUTBOX_RECORD_NOT_FOUND", "The outbox record was not found.", request).model_dump())

    @app.exception_handler(OutboxRecordNotDeadLetterException)
    async def handle_outbox_record_not_dead_letter(request: Request, exc: OutboxRecordNotDeadLetterException) -> JSONResponse:
        # SPEC-TG-028: replay is only meaningful for a DEAD_LETTER row — 409,
        # not 400: the request is well-formed, the record just isn't in a
        # replayable state right now.
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("OUTBOX_RECORD_NOT_DEAD_LETTER", "The outbox record is not in DEAD_LETTER status.", request).model_dump())

    @app.exception_handler(InvalidToolRequestTransitionException)
    @app.exception_handler(InvalidToolExecutionTransitionException)
    @app.exception_handler(InvalidConnectorHealthTransitionException)
    @app.exception_handler(InvalidApprovalLinkageTransitionException)
    async def handle_invalid_transition(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("INVALID_STATE_TRANSITION", "The requested transition is not allowed from the current state.", request).model_dump())

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("unexpected error handling %s %s", request.method, request.url.path)
        return JSONResponse(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=_body("INTERNAL_ERROR", "An unexpected error occurred.", request).model_dump())
