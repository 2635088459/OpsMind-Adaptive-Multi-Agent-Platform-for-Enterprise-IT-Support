"""Maps application and domain exceptions to a shared JSON error envelope — mirrors
memory-knowledge-service's own interfaces.errors. Never exposes stack traces, internal
exception class names, or persisted payload contents. 05-api-contracts §"API 原则":
"command API 必须区分 validation error、conflict、not found、permission denied、gate
failed 和 dependency unavailable."
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from evaluationimprovement.application.exceptions import (
    BaselineRunNotFoundException,
    CandidateNotFoundException,
    DatasetNotFoundException,
    DatasetVersionConflictException,
    GatePolicyNotFoundException,
    GraderNotFoundException,
    IdempotencyKeyReusedException,
    IncompleteRunException,
    OptimisticConcurrencyConflictException,
    ReportNotFoundException,
    RunKeyConflictException,
    RunNotFoundException,
    StaleResultException,
    TestCaseNotFoundException,
    UnauthorizedActionException,
)
from evaluationimprovement.domain.exceptions import (
    CandidateMissingApprovalException,
    CandidateMissingBenchmarkException,
    DatasetHasNoTestCasesException,
    MissingVersionBindingException,
    SelfApprovalNotAllowedException,
    SelfReviewNotAllowedException,
)
from evaluationimprovement.domain.state_machine import InvalidStateTransitionException

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
    async def handle_validation(request: Request, exc: RequestValidationError) -> JSONResponse:  # noqa: ARG001
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("VALIDATION_ERROR", "The request is invalid.", request).model_dump())

    @app.exception_handler(ValueError)
    @app.exception_handler(DatasetHasNoTestCasesException)
    @app.exception_handler(MissingVersionBindingException)
    @app.exception_handler(CandidateMissingBenchmarkException)
    @app.exception_handler(CandidateMissingApprovalException)
    async def handle_value_error(request: Request, exc: Exception) -> JSONResponse:  # noqa: ARG001
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("EVALUATION_VALIDATION_FAILED", "The request is invalid.", request).model_dump())

    @app.exception_handler(DatasetNotFoundException)
    @app.exception_handler(TestCaseNotFoundException)
    @app.exception_handler(RunNotFoundException)
    @app.exception_handler(ReportNotFoundException)
    @app.exception_handler(CandidateNotFoundException)
    @app.exception_handler(GatePolicyNotFoundException)
    @app.exception_handler(BaselineRunNotFoundException)
    async def handle_not_found(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("NOT_FOUND", str(exc), request).model_dump())

    @app.exception_handler(DatasetVersionConflictException)
    @app.exception_handler(RunKeyConflictException)
    @app.exception_handler(IdempotencyKeyReusedException)
    @app.exception_handler(OptimisticConcurrencyConflictException)
    async def handle_conflict(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("CONFLICT", str(exc), request).model_dump())

    @app.exception_handler(SelfReviewNotAllowedException)
    @app.exception_handler(SelfApprovalNotAllowedException)
    @app.exception_handler(UnauthorizedActionException)
    async def handle_permission_denied(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=_body("PERMISSION_DENIED", str(exc), request).model_dump())

    @app.exception_handler(InvalidStateTransitionException)
    async def handle_invalid_transition(request: Request, exc: InvalidStateTransitionException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("INVALID_STATE_TRANSITION", str(exc), request).model_dump())

    @app.exception_handler(IncompleteRunException)
    @app.exception_handler(StaleResultException)
    async def handle_gate_dependency(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("GATE_NOT_READY", str(exc), request).model_dump())

    @app.exception_handler(GraderNotFoundException)
    async def handle_grader_not_found(request: Request, exc: GraderNotFoundException) -> JSONResponse:
        logger.error("grader not found: %s", exc)
        return JSONResponse(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, content=_body("DEPENDENCY_UNAVAILABLE", "No grader is registered for this dimension.", request).model_dump())

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("unexpected error handling %s %s", request.method, request.url.path)
        return JSONResponse(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=_body("INTERNAL_ERROR", "An unexpected error occurred.", request).model_dump())
