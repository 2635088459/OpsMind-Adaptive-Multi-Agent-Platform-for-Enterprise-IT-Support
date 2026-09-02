"""SPEC-ARO-038 05-api-contracts "POST /api/v1/conversations": pydantic response model
for the conversation REST surface. Matches domain 09's own `05-api-contracts` §2.1
response shape ({conversationId, startedAt}) exactly — the request body is empty
(title/description/category are supplied on the first message, not at conversation
start), so no request model is needed here.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class StartConversationResponse(BaseModel):
    conversation_id: UUID
    started_at: datetime


class SendMessageRequest(BaseModel):
    text: str = Field(min_length=1)
    attachment_refs: list[str] = Field(default_factory=list)


class MessageTurnResponse(BaseModel):
    """SPEC-ARO-039 05-api-contracts: a discriminated union, exactly one of
    `{type: "text", text}` / `{type: "proposedAction", actionId, summary, riskLevel,
    requiresConfirmation}` / `{type: "escalation", ticketId, displayId, reason,
    assignedTeam}` — modeled here as one flat, mostly-optional model (pydantic has no
    ergonomic discriminated-union response_model support for this shape) rather than
    three separate response schemas; `type` is the only field every response always sets.
    """

    type: str
    text: str | None = None
    action_id: str | None = None
    summary: str | None = None
    risk_level: str | None = None
    requires_confirmation: bool | None = None
    ticket_id: UUID | None = None
    display_id: str | None = None
    reason: str | None = None
    assigned_team: str | None = None


class ActionOutcomeResponse(BaseModel):
    """SPEC-ARO-040 05-api-contracts: confirm's response is
    `{outcome: "done" | "still-processing" | "awaiting-approval"}`; decline's is
    `{outcome: "declined"}`.
    """

    outcome: str


class ConversationDetailResponse(BaseModel):
    """SPEC-ARO-042 05-api-contracts "GET /api/v1/conversations/{conversationId}"."""

    conversation_id: UUID
    state: str
    started_at: datetime
    updated_at: datetime
