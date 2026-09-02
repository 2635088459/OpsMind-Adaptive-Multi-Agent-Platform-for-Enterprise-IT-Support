"""13-package-and-class-design §"Interfaces": "Controllers do not contain business
rules. They only perform request validation, auth, and DTO mapping." — the
conversation REST surface's own mapping, mirroring interfaces.rest.mapper's shape.
"""

from __future__ import annotations

import uuid

from agentruntime.application.commands import (
    ConfirmActionCommand,
    DeclineActionCommand,
    SendMessageCommand,
    StartConversationCommand,
)
from agentruntime.application.views import (
    ActionOutcomeView,
    ConversationDetailView,
    ConversationView,
    MessageTurnView,
)
from agentruntime.domain.ids import AgentTaskId, IdempotencyKey, WorkflowInstanceId
from agentruntime.interfaces.conversation.schemas import (
    ActionOutcomeResponse,
    ConversationDetailResponse,
    MessageTurnResponse,
    SendMessageRequest,
    StartConversationResponse,
)


def to_start_conversation_command(requester_subject: str, forwarded_bearer_token: str, idempotency_key: str) -> StartConversationCommand:
    return StartConversationCommand(
        requester_subject=requester_subject, forwarded_bearer_token=forwarded_bearer_token,
        idempotency_key=IdempotencyKey(idempotency_key),
    )


def to_response(view: ConversationView) -> StartConversationResponse:
    return StartConversationResponse(conversation_id=view.conversation_id.value, started_at=view.started_at)


def to_send_message_command(
    conversation_id: uuid.UUID, requester_subject: str, request: SendMessageRequest, idempotency_key: str,
) -> SendMessageCommand:
    return SendMessageCommand(
        conversation_id=WorkflowInstanceId(conversation_id), requester_subject=requester_subject, text=request.text,
        attachment_refs=tuple(request.attachment_refs), idempotency_key=IdempotencyKey(idempotency_key),
    )


def to_message_turn_response(view: MessageTurnView) -> MessageTurnResponse:
    return MessageTurnResponse(
        type=view.kind, text=view.text, action_id=view.action_id, summary=view.action_summary,
        risk_level=view.action_risk_level, requires_confirmation=True if view.kind == "proposedAction" else None,
        ticket_id=uuid.UUID(view.ticket_id) if view.ticket_id else None, display_id=view.display_id,
        reason=view.reason, assigned_team=view.assigned_team,
    )


def to_conversation_detail_response(view: ConversationDetailView) -> ConversationDetailResponse:
    return ConversationDetailResponse(
        conversation_id=view.conversation_id.value, state=view.state.name, started_at=view.started_at,
        updated_at=view.updated_at,
    )


def to_confirm_action_command(
    conversation_id: uuid.UUID, action_id: uuid.UUID, requester_subject: str, idempotency_key: str,
) -> ConfirmActionCommand:
    return ConfirmActionCommand(
        conversation_id=WorkflowInstanceId(conversation_id), action_id=AgentTaskId(action_id),
        requester_subject=requester_subject, idempotency_key=IdempotencyKey(idempotency_key),
    )


def to_decline_action_command(
    conversation_id: uuid.UUID, action_id: uuid.UUID, requester_subject: str, idempotency_key: str,
) -> DeclineActionCommand:
    return DeclineActionCommand(
        conversation_id=WorkflowInstanceId(conversation_id), action_id=AgentTaskId(action_id),
        requester_subject=requester_subject, idempotency_key=IdempotencyKey(idempotency_key),
    )


def to_action_outcome_response(view: ActionOutcomeView) -> ActionOutcomeResponse:
    return ActionOutcomeResponse(outcome=view.outcome)
