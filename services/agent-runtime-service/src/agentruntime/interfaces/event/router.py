"""13-package-and-class-design §"Interfaces": "Event listener." Depends only on
RuntimeEventConsumerPort; carries no business rules. Manual/ops trigger until a real
RabbitMQ async consumer exists (phase-06 external-event-consumption, per the frozen
Python Agent Runtime baseline's "RabbitMQ async client" dependency) — this endpoint is
the seam that consumer will call into instead of a client hitting it directly.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from agentruntime.application.ports_in import (
    ImprovementConsumerPort,
    RuntimeEventConsumerPort,
    TicketCreatedConsumerPort,
    TicketCycleConsumerPort,
)
from agentruntime.container import (
    get_improvement_consumer_port,
    get_runtime_event_consumer_port,
    get_ticket_created_consumer_port,
    get_ticket_cycle_consumer_port,
)
from agentruntime.interfaces.event.mapper import (
    to_envelope,
    to_improvement_promoted_command,
    to_improvement_rollback_command,
    to_ticket_cancelled_command,
    to_ticket_created_command,
    to_ticket_reopened_command,
)
from agentruntime.interfaces.event.schemas import (
    ImprovementPromotedEventRequest,
    ImprovementRollbackEventRequest,
    RuntimeEventRequest,
    TicketCancelledEventRequest,
    TicketCreatedEventRequest,
    TicketReopenedEventRequest,
)

router = APIRouter(prefix="/internal/agent-runtime/v1/events", tags=["events"])


@router.post("")
def ingest_runtime_event(request: RuntimeEventRequest, port: RuntimeEventConsumerPort = Depends(get_runtime_event_consumer_port)) -> dict[str, object]:
    applied = port.consume(to_envelope(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/ticket-created")
def ingest_ticket_created(
    request: TicketCreatedEventRequest, port: TicketCreatedConsumerPort = Depends(get_ticket_created_consumer_port)
) -> dict[str, object]:
    """SPEC-ARO-005 06-event-contracts "ticket.created.v1". A dedicated route rather than
    a case inside POST /events: that endpoint's RuntimeEventRequest requires
    workflow_instance_id, which ticket.created structurally cannot supply.
    """
    applied = port.consume(to_ticket_created_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/ticket-cancelled")
def ingest_ticket_cancelled(
    request: TicketCancelledEventRequest, port: TicketCycleConsumerPort = Depends(get_ticket_cycle_consumer_port)
) -> dict[str, object]:
    """SPEC-ARO-023 06-event-contracts (02-ticket-workflow PUB-014 "ticket.cancelled.v1").
    A dedicated route for the same structural reason ticket-created has one: Ticket
    Workflow carries no workflow_instance_id for this Runtime to look up.
    """
    applied = port.consume_cancelled(to_ticket_cancelled_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/ticket-reopened")
def ingest_ticket_reopened(
    request: TicketReopenedEventRequest, port: TicketCycleConsumerPort = Depends(get_ticket_cycle_consumer_port)
) -> dict[str, object]:
    """SPEC-ARO-023 06-event-contracts (02-ticket-workflow PUB-015 "ticket.reopened.v1")."""
    applied = port.consume_reopened(to_ticket_reopened_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/improvement-promoted")
def ingest_improvement_promoted(
    request: ImprovementPromotedEventRequest, port: ImprovementConsumerPort = Depends(get_improvement_consumer_port)
) -> dict[str, object]:
    """`improvement.promoted.v1` — adopt evaluation-improvement-service's promoted
    change as the active version of its target component. Dedicated route (no
    workflow_instance_id: this is a platform-wide config change).
    """
    applied = port.consume_promoted(to_improvement_promoted_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/improvement-rollback")
def ingest_improvement_rollback(
    request: ImprovementRollbackEventRequest, port: ImprovementConsumerPort = Depends(get_improvement_consumer_port)
) -> dict[str, object]:
    """`improvement.rollback.requested.v1` — revert the component the named
    candidate promoted back to its built-in default.
    """
    applied = port.consume_rollback(to_improvement_rollback_command(request))
    return {"eventId": request.event_id, "applied": applied}
