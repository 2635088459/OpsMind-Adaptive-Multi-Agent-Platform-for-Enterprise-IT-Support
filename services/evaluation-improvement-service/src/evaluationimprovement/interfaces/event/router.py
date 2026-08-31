"""13-package-and-class-design §"Interfaces": event listener. Depends only on
CrossDomainEventConsumerPort/ApprovalDecisionEventConsumerPort; carries no business
rules. Manual/ops trigger until a real RabbitMQ async consumer exists — this endpoint
is the seam that consumer will call into instead of a client hitting it directly,
mirroring memory-knowledge-service's own interfaces/event/router.py precedent
exactly (see infrastructure.messaging.rabbitmq_consumer's own module docstring for
where the real broker consumer will eventually live).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from evaluationimprovement.application.ports_in import ApprovalDecisionEventConsumerPort, CrossDomainEventConsumerPort
from evaluationimprovement.container import get_approval_decision_event_consumer_port, get_cross_domain_event_consumer_port
from evaluationimprovement.interfaces.event.mapper import (
    to_consume_approval_denied_command,
    to_consume_approval_granted_command,
    to_consume_memory_retrieval_completed_command,
    to_consume_ticket_reopened_command,
    to_consume_ticket_resolved_command,
    to_consume_tool_completed_command,
    to_consume_workflow_completed_command,
    to_consume_workflow_failed_command,
)
from evaluationimprovement.interfaces.event.schemas import (
    ApprovalDeniedEventRequest,
    ApprovalGrantedEventRequest,
    MemoryRetrievalCompletedEventRequest,
    TicketReopenedEventRequest,
    TicketResolvedEventRequest,
    ToolCompletedEventRequest,
    WorkflowCompletedEventRequest,
    WorkflowFailedEventRequest,
)

router = APIRouter(prefix="/internal/evaluation/v1/events", tags=["events"])


@router.post("/ticket-resolved")
def ingest_ticket_resolved(
    request: TicketResolvedEventRequest, port: CrossDomainEventConsumerPort = Depends(get_cross_domain_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-030 06-event-contracts (02-ticket-workflow's own "ticket.resolved.v1")."""
    applied = port.consume_ticket_resolved(to_consume_ticket_resolved_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/ticket-reopened")
def ingest_ticket_reopened(
    request: TicketReopenedEventRequest, port: CrossDomainEventConsumerPort = Depends(get_cross_domain_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-030 06-event-contracts (02-ticket-workflow's own "ticket.reopened.v1")."""
    applied = port.consume_ticket_reopened(to_consume_ticket_reopened_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/workflow-completed")
def ingest_workflow_completed(
    request: WorkflowCompletedEventRequest, port: CrossDomainEventConsumerPort = Depends(get_cross_domain_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-030 06-event-contracts (03-agent-runtime-orchestration's own "workflow.completed.v1")."""
    applied = port.consume_workflow_completed(to_consume_workflow_completed_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/workflow-failed")
def ingest_workflow_failed(
    request: WorkflowFailedEventRequest, port: CrossDomainEventConsumerPort = Depends(get_cross_domain_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-030 06-event-contracts (03-agent-runtime-orchestration's own "workflow.failed.v1")."""
    applied = port.consume_workflow_failed(to_consume_workflow_failed_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/tool-completed")
def ingest_tool_completed(
    request: ToolCompletedEventRequest, port: CrossDomainEventConsumerPort = Depends(get_cross_domain_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-031 06-event-contracts (05-tool-integration-gateway's own "tool.completed.v1")."""
    applied = port.consume_tool_completed(to_consume_tool_completed_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/memory-retrieval-completed")
def ingest_memory_retrieval_completed(
    request: MemoryRetrievalCompletedEventRequest, port: CrossDomainEventConsumerPort = Depends(get_cross_domain_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-031 06-event-contracts ("memory.retrieval.completed.v1" — see
    ConsumeMemoryRetrievalCompletedCommand's own docstring: no real 04-memory-
    knowledge publisher exists yet).
    """
    applied = port.consume_memory_retrieval_completed(to_consume_memory_retrieval_completed_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/approval-granted")
def ingest_approval_granted(
    request: ApprovalGrantedEventRequest, port: ApprovalDecisionEventConsumerPort = Depends(get_approval_decision_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-032 06-event-contracts (06-policy-approval-governance's own "approval.granted.v1")."""
    applied = port.consume_granted(to_consume_approval_granted_command(request))
    return {"eventId": request.event_id, "applied": applied}


@router.post("/approval-denied")
def ingest_approval_denied(
    request: ApprovalDeniedEventRequest, port: ApprovalDecisionEventConsumerPort = Depends(get_approval_decision_event_consumer_port),
) -> dict[str, object]:
    """SPEC-EI-032 06-event-contracts (06-policy-approval-governance's own "approval.denied.v1")."""
    applied = port.consume_denied(to_consume_approval_denied_command(request))
    return {"eventId": request.event_id, "applied": applied}
