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
    ActionNotAwaitingConfirmationException,
    ActionNotFoundException,
    AgentTaskNotFoundException,
    AgentTaskVersionConflictException,
    AutomationNotAllowedException,
    CapabilityNotAuthorizedException,
    CheckpointNotFoundException,
    ClaimTokenMismatchException,
    ConversationAccessDeniedException,
    ConversationNotFoundException,
    DefinitionVersionMismatchException,
    DuplicateActiveWorkflowInstanceException,
    EscalationRoutingNotConfiguredException,
    GovernanceApprovalRequestFailedException,
    IdempotencyKeyReusedException,
    OutboundAuthenticationException,
    PauseCheckpointNotFoundException,
    PoisonEventNotFoundException,
    PoisonRuntimeEventException,
    StalePauseGenerationException,
    StaleRuntimeEventException,
    StaleWorkflowVersionException,
    TicketCreationFailedException,
    TicketTriageFailedException,
    ToolRequestNotFoundException,
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

    @app.exception_handler(PoisonEventNotFoundException)
    async def handle_poison_event_not_found(request: Request, exc: PoisonEventNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("POISON_EVENT_NOT_FOUND", "The poison event was not found.", request).model_dump())

    @app.exception_handler(ToolRequestNotFoundException)
    async def handle_tool_request_not_found(request: Request, exc: ToolRequestNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("TOOL_REQUEST_NOT_FOUND", "The tool request was not found.", request).model_dump())

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

    @app.exception_handler(CapabilityNotAuthorizedException)
    async def handle_capability_not_authorized(request: Request, exc: CapabilityNotAuthorizedException) -> JSONResponse:
        """SPEC-ARO-032 11-security §"Authorization": 403, not 409 like this module's other
        policy rejections (e.g. AutomationNotAllowedException) — those are state-conflict
        flavored ("the ticket isn't in a startable status right now"), while this one is
        a true authenticated-but-not-authorized denial, the standard case for 403.
        """
        return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=_body(
            "CAPABILITY_NOT_AUTHORIZED", "The claiming agent role is not authorized for the requested capability.", request
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

    @app.exception_handler(PoisonRuntimeEventException)
    async def handle_poison_event(request: Request, exc: PoisonRuntimeEventException) -> JSONResponse:
        logger.error("poison runtime event %s: %s", exc.event_id, exc.reason)
        return JSONResponse(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, content=_body(
            "POISON_EVENT", "The event payload could not be processed and was recorded for manual review.", request
        ).model_dump())

    @app.exception_handler(DefinitionVersionMismatchException)
    async def handle_definition_version_mismatch(request: Request, exc: DefinitionVersionMismatchException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "DEFINITION_VERSION_MISMATCH", "The workflow instance is bound to a different definition version.", request
        ).model_dump())

    @app.exception_handler(TicketCreationFailedException)
    async def handle_ticket_creation_failed(request: Request, exc: TicketCreationFailedException) -> JSONResponse:
        """SPEC-ARO-038: the outbound call to 02-ticket-workflow did not succeed —
        502, not 500: this service functioned correctly, a downstream dependency did
        not.
        """
        logger.error("ticket creation failed: %s", exc.reason)
        return JSONResponse(status_code=status.HTTP_502_BAD_GATEWAY, content=_body(
            "TICKET_CREATION_FAILED", "Could not create the ticket backing this conversation.", request
        ).model_dump())

    @app.exception_handler(OutboundAuthenticationException)
    async def handle_outbound_authentication_failed(request: Request, exc: OutboundAuthenticationException) -> JSONResponse:
        """SPEC-ARO-043 domain-rules: "fails closed" — this service could not obtain
        its own outbound service identity token; 502, the same "we function, a
        dependency does not" posture as TicketCreationFailedException.
        """
        logger.error("outbound authentication failed: %s", exc.reason)
        return JSONResponse(status_code=status.HTTP_502_BAD_GATEWAY, content=_body(
            "OUTBOUND_AUTHENTICATION_FAILED", "Could not authenticate to a downstream service.", request
        ).model_dump())

    @app.exception_handler(TicketTriageFailedException)
    async def handle_ticket_triage_failed(request: Request, exc: TicketTriageFailedException) -> JSONResponse:
        """SPEC-ARO-041: the outbound call to 02-ticket-workflow's real triage endpoint
        did not succeed — 502, the same "we function, a dependency did not" posture as
        TicketCreationFailedException.
        """
        logger.error("ticket triage failed: %s", exc.reason)
        return JSONResponse(status_code=status.HTTP_502_BAD_GATEWAY, content=_body(
            "TICKET_TRIAGE_FAILED", "Could not escalate this conversation's ticket.", request
        ).model_dump())

    @app.exception_handler(EscalationRoutingNotConfiguredException)
    async def handle_escalation_routing_not_configured(request: Request, exc: EscalationRoutingNotConfiguredException) -> JSONResponse:
        """SPEC-ARO-041: no real categoryId/supportQueueId is configured for this
        deployment — 502, an operator-fixable deployment gap, not a client error.
        """
        logger.error("escalation routing is not configured")
        return JSONResponse(status_code=status.HTTP_502_BAD_GATEWAY, content=_body(
            "ESCALATION_ROUTING_NOT_CONFIGURED", "Escalation routing is not configured for this deployment.", request
        ).model_dump())

    @app.exception_handler(GovernanceApprovalRequestFailedException)
    async def handle_governance_approval_request_failed(request: Request, exc: GovernanceApprovalRequestFailedException) -> JSONResponse:
        """SPEC-ARO-040: the outbound call to 06-policy-approval-governance did not
        succeed — 502, the same "we function, a dependency did not" posture as
        TicketTriageFailedException.
        """
        logger.error("governance approval request failed: %s", exc.reason)
        return JSONResponse(status_code=status.HTTP_502_BAD_GATEWAY, content=_body(
            "GOVERNANCE_APPROVAL_REQUEST_FAILED", "Could not create the governance approval request.", request
        ).model_dump())

    @app.exception_handler(ActionNotFoundException)
    async def handle_action_not_found(request: Request, exc: ActionNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body(
            "ACTION_NOT_FOUND", "The action was not found.", request
        ).model_dump())

    @app.exception_handler(ActionNotAwaitingConfirmationException)
    async def handle_action_not_awaiting_confirmation(request: Request, exc: ActionNotAwaitingConfirmationException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body(
            "ACTION_NOT_AWAITING_CONFIRMATION", "This action is not awaiting confirmation.", request
        ).model_dump())

    @app.exception_handler(ConversationNotFoundException)
    async def handle_conversation_not_found(request: Request, exc: ConversationNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body(
            "CONVERSATION_NOT_FOUND", "The conversation was not found.", request
        ).model_dump())

    @app.exception_handler(ConversationAccessDeniedException)
    async def handle_conversation_access_denied(request: Request, exc: ConversationAccessDeniedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=_body(
            "CONVERSATION_ACCESS_DENIED", "This conversation does not belong to the calling employee.", request
        ).model_dump())

    @app.exception_handler(PauseCheckpointNotFoundException)
    async def handle_pause_checkpoint_not_found(request: Request, exc: PauseCheckpointNotFoundException) -> JSONResponse:
        logger.error("resume blocked: no PAUSE_POINT checkpoint for %s", exc.workflow_instance_id)
        return JSONResponse(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=_body(
            "PAUSE_CHECKPOINT_NOT_FOUND", "The workflow instance is paused but has no recorded pause checkpoint.", request
        ).model_dump())

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("unexpected error handling %s %s", request.method, request.url.path)
        return JSONResponse(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=_body("INTERNAL_ERROR", "An unexpected error occurred.", request).model_dump())
