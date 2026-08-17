"""Mapping boundary for the memory-source event listener — only ever builds
application-layer commands from wire schemas, never leaks a memoryknowledge.domain
type into the wire shape's own field types beyond plain str/UUID/datetime.
"""

from __future__ import annotations

from memoryknowledge.application.commands import (
    ConsumeTicketClosedCommand,
    ConsumeTicketResolvedCommand,
    ConsumeWorkflowCompletedCommand,
    ConsumeWorkflowFailedCommand,
)
from memoryknowledge.domain.ids import CorrelationId, TicketCycleId, TicketId, WorkflowInstanceId
from memoryknowledge.interfaces.event.schemas import (
    TicketClosedEventRequest,
    TicketResolvedEventRequest,
    WorkflowCompletedEventRequest,
    WorkflowFailedEventRequest,
)


def to_consume_ticket_resolved_command(request: TicketResolvedEventRequest) -> ConsumeTicketResolvedCommand:
    return ConsumeTicketResolvedCommand(
        event_id=request.event_id, ticket_id=TicketId(request.ticket_id), ticket_cycle_id=TicketCycleId(request.ticket_cycle_id),
        resolution_code=request.resolution_code, resolution_summary=request.resolution_summary,
        resolved_by=request.resolved_by, resolved_at=request.resolved_at, correlation_id=CorrelationId(request.correlation_id),
    )


def to_consume_ticket_closed_command(request: TicketClosedEventRequest) -> ConsumeTicketClosedCommand:
    return ConsumeTicketClosedCommand(
        event_id=request.event_id, ticket_id=TicketId(request.ticket_id), ticket_cycle_id=TicketCycleId(request.ticket_cycle_id),
        close_reason_code=request.close_reason_code, close_reason=request.close_reason,
        closed_by=request.closed_by, closed_at=request.closed_at, correlation_id=CorrelationId(request.correlation_id),
    )


def to_consume_workflow_completed_command(request: WorkflowCompletedEventRequest) -> ConsumeWorkflowCompletedCommand:
    return ConsumeWorkflowCompletedCommand(
        event_id=request.event_id, workflow_instance_id=WorkflowInstanceId(request.workflow_instance_id),
        ticket_id=TicketId(request.ticket_id), from_state=request.from_state, to_state=request.to_state,
        workflow_version=request.workflow_version, occurred_at=request.occurred_at, correlation_id=CorrelationId(request.correlation_id),
    )


def to_consume_workflow_failed_command(request: WorkflowFailedEventRequest) -> ConsumeWorkflowFailedCommand:
    return ConsumeWorkflowFailedCommand(
        event_id=request.event_id, workflow_instance_id=WorkflowInstanceId(request.workflow_instance_id),
        ticket_id=TicketId(request.ticket_id), from_state=request.from_state, to_state=request.to_state,
        workflow_version=request.workflow_version, failure_reason=request.failure_reason, occurred_at=request.occurred_at,
        correlation_id=CorrelationId(request.correlation_id),
    )
