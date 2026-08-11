"""Maps application and domain exceptions to a shared JSON error envelope, mirroring
the sibling Java services' GlobalRestExceptionHandler + ErrorResponse. Never exposes
stack traces, internal exception class names, or persisted payload contents.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from agentruntime.application.exceptions import (
    AgentTaskNotFoundException,
    AgentTaskVersionConflictException,
    AutomationNotAllowedException,
    CheckpointNotFoundException,
    ClaimTokenMismatchException,
    DefinitionVersionMismatchException,
    DuplicateActiveWorkflowInstanceException,
    IdempotencyKeyReusedException,
    StalePauseGenerationException,
    StaleRuntimeEventException,
    StaleWorkflowVersionException,
    WorkflowInstanceNotFoundException,
    WorkflowInstanceVersionConflictException,
    WorkflowNotRunningException,
)
from agentruntime.domain.exceptions import (
    AgentTaskAlreadyClaimedException,
    AgentTaskDependencyNotSatisfiedException,
    InvalidAgentTaskTransitionException,
    InvalidWorkflowStateException,
    InvalidWorkflowTransitionException,
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
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("VALIDATION_ERROR", "The request is invalid.", request).model_dump())

    @app.exception_handler(ValueError)
    async def handle_value_error(request: Request, exc: ValueError) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("VALIDATION_ERROR", "The request is invalid.", request).model_dump())

    @app.exception_handler(WorkflowInstanceNotFoundException)
    async def handle_workflow_instance_not_found(request: Request, exc: WorkflowInstanceNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("WORKFLOW_INSTANCE_NOT_FOUND", "The workflow instance was not found.", request).model_dump())

    @app.exception_handler(AgentTaskNotFoundException)
    async def handle_agent_task_not_found(request: Request, exc: AgentTaskNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("AGENT_TASK_NOT_FOUND", "The agent task was not found.", request).model_dump())

    @app.exception_handler(CheckpointNotFoundException)
    async def handle_checkpoint_not_found(request: Request, exc: CheckpointNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body(
            "CHECKPOINT_NOT_FOUND", "The workflow instance has no recorded checkpoint.", request
        ).model_dump())

    @app.exception_handler(DuplicateActiveWorkflowInstanceException)
    async def handle_duplicate_active(request: Request, exc: DuplicateActiveWorkflowInstanceException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "DUPLICATE_ACTIVE_WORKFLOW_INSTANCE", "An active workflow instance already exists for this ticket cycle and workflow type.", request
        ).model_dump())

    @app.exception_handler(IdempotencyKeyReusedException)
    async def handle_idempotency_key_reused(request: Request, exc: IdempotencyKeyReusedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "IDEMPOTENCY_KEY_REUSED", "A different idempotency key already produced this result.", request
        ).model_dump())

    @app.exception_handler(WorkflowInstanceVersionConflictException)
    @app.exception_handler(AgentTaskVersionConflictException)
    async def handle_version_conflict(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("VERSION_CONFLICT", "The resource was modified concurrently.", request).model_dump())

    @app.exception_handler(InvalidWorkflowTransitionException)
    @app.exception_handler(InvalidWorkflowStateException)
    @app.exception_handler(InvalidAgentTaskTransitionException)
    async def handle_invalid_transition(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "INVALID_STATE_TRANSITION", "The requested transition is not allowed from the current state.", request
        ).model_dump())

    @app.exception_handler(AgentTaskDependencyNotSatisfiedException)
    async def handle_dependency_not_satisfied(request: Request, exc: AgentTaskDependencyNotSatisfiedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "AGENT_TASK_DEPENDENCY_NOT_SATISFIED", "One or more dependsOn tasks have not completed.", request
        ).model_dump())

    @app.exception_handler(AgentTaskAlreadyClaimedException)
    async def handle_agent_task_already_claimed(request: Request, exc: AgentTaskAlreadyClaimedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "AGENT_TASK_ALREADY_CLAIMED", "The agent task is already claimed under an unexpired lease.", request
        ).model_dump())

    @app.exception_handler(ClaimTokenMismatchException)
    async def handle_claim_token_mismatch(request: Request, exc: ClaimTokenMismatchException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "CLAIM_TOKEN_MISMATCH", "The submitted claim token does not match the agent task's current lease.", request
        ).model_dump())

    @app.exception_handler(StalePauseGenerationException)
    async def handle_stale_pause_generation(request: Request, exc: StalePauseGenerationException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "STALE_PAUSE_GENERATION", "The workflow was paused or resumed since this task was claimed.", request
        ).model_dump())

    @app.exception_handler(StaleWorkflowVersionException)
    async def handle_stale_workflow_version(request: Request, exc: StaleWorkflowVersionException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "STALE_WORKFLOW_VERSION", "The workflow instance version has changed since this task was claimed.", request
        ).model_dump())

    @app.exception_handler(WorkflowNotRunningException)
    async def handle_workflow_not_running(request: Request, exc: WorkflowNotRunningException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "WORKFLOW_NOT_RUNNING", "The workflow instance is not RUNNING; agent tasks cannot be claimed.", request
        ).model_dump())

    @app.exception_handler(AutomationNotAllowedException)
    async def handle_automation_not_allowed(request: Request, exc: AutomationNotAllowedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "AUTOMATION_NOT_ALLOWED", "The ticket is in a status that does not allow automation to start.", request
        ).model_dump())

    @app.exception_handler(StaleRuntimeEventException)
    async def handle_stale_event(request: Request, exc: StaleRuntimeEventException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("STALE_RUNTIME_EVENT", "The event is stale and was not applied.", request).model_dump())

    @app.exception_handler(DefinitionVersionMismatchException)
    async def handle_definition_version_mismatch(request: Request, exc: DefinitionVersionMismatchException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "DEFINITION_VERSION_MISMATCH", "The workflow instance is bound to a different definition version.", request
        ).model_dump())

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("unexpected error handling %s %s", request.method, request.url.path)
        return JSONResponse(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=_body("INTERNAL_ERROR", "An unexpected error occurred.", request).model_dump())
