"""13-package-and-class-design §"Interfaces": event listener. Depends only on
TicketMemorySourceEventConsumerPort/WorkflowMemorySourceEventConsumerPort; carries no
business rules. Manual/ops trigger until a real RabbitMQ async consumer exists — this
endpoint is the seam that consumer will call into instead of a client hitting it
directly, mirroring agent-runtime-service's own interfaces/event/router.py precedent
exactly.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from memoryknowledge.application.ports_in import TicketMemorySourceEventConsumerPort, WorkflowMemorySourceEventConsumerPort
from memoryknowledge.container import get_ticket_memory_source_event_consumer_port, get_workflow_memory_source_event_consumer_port
from memoryknowledge.interfaces.event.mapper import (
    to_consume_ticket_closed_command,
    to_consume_ticket_resolved_command,
    to_consume_workflow_completed_command,
    to_consume_workflow_failed_command,
)
from memoryknowledge.interfaces.event.schemas import (
    TicketClosedEventRequest,
    TicketResolvedEventRequest,
    WorkflowCompletedEventRequest,
    WorkflowFailedEventRequest,
)

router = APIRouter(prefix="/internal/memory/v1/events", tags=["events"])


@router.post("/ticket-resolved")
def ingest_ticket_resolved(
    request: TicketResolvedEventRequest, port: TicketMemorySourceEventConsumerPort = Depends(get_ticket_memory_source_event_consumer_port),
) -> dict[str, object]:
    """SPEC-MK-010 06-event-contracts (02-ticket-workflow PUB-012 "ticket.resolved.v1")."""
    applied = port.consume_resolved(to_consume_ticket_resolved_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/ticket-closed")
def ingest_ticket_closed(
    request: TicketClosedEventRequest, port: TicketMemorySourceEventConsumerPort = Depends(get_ticket_memory_source_event_consumer_port),
) -> dict[str, object]:
    """SPEC-MK-010 06-event-contracts (02-ticket-workflow PUB-013 "ticket.closed.v1")."""
    applied = port.consume_closed(to_consume_ticket_closed_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/workflow-completed")
def ingest_workflow_completed(
    request: WorkflowCompletedEventRequest, port: WorkflowMemorySourceEventConsumerPort = Depends(get_workflow_memory_source_event_consumer_port),
) -> dict[str, object]:
    """SPEC-MK-022 06-event-contracts (03-agent-runtime-orchestration "workflow.completed.v1")."""
    applied = port.consume_completed(to_consume_workflow_completed_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/workflow-failed")
def ingest_workflow_failed(
    request: WorkflowFailedEventRequest, port: WorkflowMemorySourceEventConsumerPort = Depends(get_workflow_memory_source_event_consumer_port),
) -> dict[str, object]:
    """SPEC-MK-022 06-event-contracts (03-agent-runtime-orchestration "workflow.failed.v1")."""
    applied = port.consume_failed(to_consume_workflow_failed_command(request))
    return {"eventId": request.event_id, "applied": applied}
