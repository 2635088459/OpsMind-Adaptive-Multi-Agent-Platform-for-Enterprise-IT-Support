"""SPEC-ARO-001 event-contract: "Event envelope must include correlationId, causationId,
ticketId, and workflowInstanceId."
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class RuntimeEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    event_type: str = Field(min_length=1)
    producer: str = Field(min_length=1)
    schema_version: int = Field(ge=1)
    correlation_id: UUID
    causation_id: UUID
    ticket_id: UUID
    workflow_instance_id: UUID
    expected_workflow_version: int | None = None
    occurred_at: datetime
    payload: str = Field(min_length=1)


class TicketCreatedEventRequest(BaseModel):
    """SPEC-ARO-005 06-event-contracts "ticket.created.v1" — no workflow_instance_id
    field: none exists yet, creating one is the point of this event.
    """

    event_id: str = Field(min_length=1)
    event_type: str = Field(min_length=1, default="ticket.created.v1")
    producer: str = Field(min_length=1)
    schema_version: int = Field(ge=1)
    correlation_id: UUID
    causation_id: UUID
    ticket_id: UUID
    ticket_cycle_id: UUID
    priority: str = Field(min_length=1)
    category: str = Field(min_length=1)
    created_by: str = Field(min_length=1)
    occurred_at: datetime
