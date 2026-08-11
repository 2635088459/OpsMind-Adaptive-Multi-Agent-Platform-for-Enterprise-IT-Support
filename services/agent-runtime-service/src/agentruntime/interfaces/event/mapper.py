from __future__ import annotations

from agentruntime.application.commands import ConsumeTicketCreatedCommand, RuntimeEventEnvelope
from agentruntime.domain.ids import CausationId, CorrelationId, TicketCycleId, TicketId, WorkflowInstanceId
from agentruntime.interfaces.event.schemas import RuntimeEventRequest, TicketCreatedEventRequest


def to_envelope(request: RuntimeEventRequest) -> RuntimeEventEnvelope:
    return RuntimeEventEnvelope(
        event_id=request.event_id, event_type=request.event_type, producer=request.producer,
        schema_version=request.schema_version, correlation_id=CorrelationId(request.correlation_id),
        causation_id=CausationId(request.causation_id), ticket_id=TicketId(request.ticket_id),
        workflow_instance_id=WorkflowInstanceId(request.workflow_instance_id),
        expected_workflow_version=request.expected_workflow_version, occurred_at=request.occurred_at,
        payload=request.payload,
    )


def to_ticket_created_command(request: TicketCreatedEventRequest) -> ConsumeTicketCreatedCommand:
    return ConsumeTicketCreatedCommand(
        event_id=request.event_id, event_type=request.event_type, producer=request.producer,
        schema_version=request.schema_version, correlation_id=CorrelationId(request.correlation_id),
        causation_id=CausationId(request.causation_id), ticket_id=TicketId(request.ticket_id),
        ticket_cycle_id=TicketCycleId(request.ticket_cycle_id), priority=request.priority,
        category=request.category, created_by=request.created_by, occurred_at=request.occurred_at,
    )
