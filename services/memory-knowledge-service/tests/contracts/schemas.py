"""SPEC-MK-021/022 14-testing-strategy §"契约测试" (mirrors agent-runtime-service's own
tests/contracts/schemas.py): independently-authored contract models for the four
consumed event types, listing only the fields 06-event-contracts calls out as "关键
字段" — deliberately *not* imported from memoryknowledge.interfaces.event.schemas, so
a contract-vs-implementation drift shows up as a real test failure instead of two
copies of the same possibly-wrong assumption agreeing with each other.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class TicketResolvedContract(BaseModel):
    """02-ticket-workflow PUB-012 "ticket.resolved.v1"."""

    event_id: str = Field(min_length=1)
    ticket_id: UUID
    ticket_cycle_id: UUID
    resolution_code: str = Field(min_length=1)
    resolution_summary: str = Field(min_length=1)
    resolved_by: str = Field(min_length=1)
    resolved_at: datetime
    correlation_id: UUID


class TicketClosedContract(BaseModel):
    """02-ticket-workflow PUB-013 "ticket.closed.v1"."""

    event_id: str = Field(min_length=1)
    ticket_id: UUID
    ticket_cycle_id: UUID
    close_reason_code: str = Field(min_length=1)
    close_reason: str = Field(min_length=1)
    closed_by: str = Field(min_length=1)
    closed_at: datetime
    correlation_id: UUID


class WorkflowCompletedContract(BaseModel):
    """03-agent-runtime-orchestration "workflow.completed.v1"."""

    event_id: str = Field(min_length=1)
    workflow_instance_id: UUID
    ticket_id: UUID
    to_state: str = Field(min_length=1)
    workflow_version: int = Field(ge=1)
    occurred_at: datetime
    correlation_id: UUID


class WorkflowFailedContract(BaseModel):
    """03-agent-runtime-orchestration "workflow.failed.v1"."""

    event_id: str = Field(min_length=1)
    workflow_instance_id: UUID
    ticket_id: UUID
    to_state: str = Field(min_length=1)
    workflow_version: int = Field(ge=1)
    failure_reason: str = Field(min_length=1)
    occurred_at: datetime
    correlation_id: UUID
