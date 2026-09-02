"""SPEC-ARO-038 (phase-10 Conversational Intake): the first genuinely public,
employee-facing REST surface this service exposes — a real employee JWT (forwarded,
not admin-only/internal), unlike agentruntime.interfaces.rest.router's own
`/internal/agent-runtime/v1/workflows` prefix.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, Header, status

from agentruntime.application.ports_in import (
    ConversationCommandPort,
    ConversationQueryPort,
)
from agentruntime.container import (
    get_conversation_command_port,
    get_conversation_query_port,
)
from agentruntime.domain.ids import WorkflowInstanceId
from agentruntime.interfaces.conversation.mapper import (
    to_action_outcome_response,
    to_confirm_action_command,
    to_conversation_detail_response,
    to_decline_action_command,
    to_message_turn_response,
    to_response,
    to_send_message_command,
    to_start_conversation_command,
)
from agentruntime.interfaces.conversation.schemas import (
    ActionOutcomeResponse,
    ConversationDetailResponse,
    MessageTurnResponse,
    SendMessageRequest,
    StartConversationResponse,
)
from agentruntime.interfaces.conversation.security import (
    forwarded_bearer_token,
    requester_subject,
)

router = APIRouter(prefix="/api/v1/conversations", tags=["conversations"])


@router.post("", status_code=status.HTTP_201_CREATED, response_model=StartConversationResponse)
def start_conversation(
    idempotency_key: str = Header(..., alias="Idempotency-Key", min_length=1),
    subject: str = Depends(requester_subject),
    token: str = Depends(forwarded_bearer_token),
    port: ConversationCommandPort = Depends(get_conversation_command_port),
) -> StartConversationResponse:
    return to_response(port.start_conversation(to_start_conversation_command(subject, token, idempotency_key)))


@router.post("/{conversation_id}/messages", response_model=MessageTurnResponse)
def send_message(
    conversation_id: UUID,
    request: SendMessageRequest,
    idempotency_key: str = Header(..., alias="Idempotency-Key", min_length=1),
    subject: str = Depends(requester_subject),
    port: ConversationCommandPort = Depends(get_conversation_command_port),
) -> MessageTurnResponse:
    """SPEC-ARO-039 05-api-contracts "POST /api/v1/conversations/{conversationId}/
    messages". Unlike start_conversation, this endpoint never forwards the bearer
    token anywhere — SendMessageService's own outbound calls (knowledge retrieval,
    the real triage call) authenticate their own way (see those ports' own docstrings)
    — only the unverified `sub` claim is needed here, for the ownership check.
    """
    command = to_send_message_command(conversation_id, subject, request, idempotency_key)
    return to_message_turn_response(port.send_message(command))


@router.post("/{conversation_id}/actions/{action_id}/confirm", response_model=ActionOutcomeResponse)
def confirm_action(
    conversation_id: UUID,
    action_id: UUID,
    idempotency_key: str = Header(..., alias="Idempotency-Key", min_length=1),
    subject: str = Depends(requester_subject),
    port: ConversationCommandPort = Depends(get_conversation_command_port),
) -> ActionOutcomeResponse:
    """SPEC-ARO-040 05-api-contracts "POST /api/v1/conversations/{conversationId}/
    actions/{actionId}/confirm"."""
    command = to_confirm_action_command(conversation_id, action_id, subject, idempotency_key)
    return to_action_outcome_response(port.confirm_action(command))


@router.post("/{conversation_id}/actions/{action_id}/decline", response_model=ActionOutcomeResponse)
def decline_action(
    conversation_id: UUID,
    action_id: UUID,
    idempotency_key: str = Header(..., alias="Idempotency-Key", min_length=1),
    subject: str = Depends(requester_subject),
    port: ConversationCommandPort = Depends(get_conversation_command_port),
) -> ActionOutcomeResponse:
    """SPEC-ARO-040 05-api-contracts "POST /api/v1/conversations/{conversationId}/
    actions/{actionId}/decline"."""
    command = to_decline_action_command(conversation_id, action_id, subject, idempotency_key)
    return to_action_outcome_response(port.decline_action(command))


# SPEC-ARO-042: registered before "/{conversation_id}" — Starlette matches path routes
# in declaration order, and a literal "/most-recent" would otherwise never be reached
# (every request to it would instead bind to conversation_id="most-recent", failing UUID
# validation with an unrelated 422 rather than reaching this endpoint at all).
@router.get("/most-recent", response_model=ConversationDetailResponse)
def find_most_recent_conversation(
    subject: str = Depends(requester_subject), port: ConversationQueryPort = Depends(get_conversation_query_port),
) -> ConversationDetailResponse:
    """SPEC-ARO-042 05-api-contracts: supports domain 09's UC-EP-06 — a returning
    employee who does not already know their conversationId.
    """
    return to_conversation_detail_response(port.find_most_recent_conversation(subject))


@router.get("/{conversation_id}", response_model=ConversationDetailResponse)
def find_conversation(
    conversation_id: UUID, subject: str = Depends(requester_subject),
    port: ConversationQueryPort = Depends(get_conversation_query_port),
) -> ConversationDetailResponse:
    """SPEC-ARO-042 05-api-contracts "GET /api/v1/conversations/{conversationId}"."""
    return to_conversation_detail_response(port.find_conversation(WorkflowInstanceId(conversation_id), subject))
