package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ToolExecutionCompletedEventPayload;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ToolExecutionCompletedEventMapper {

    public ApplyToolExecutionCompletedCommand toCommand(ConsumedEventEnvelope envelope, ToolExecutionCompletedEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyToolExecutionCompletedCommand(
            ticketId,
            envelope.eventId(),
            payload.workflowId(),
            payload.actionId(),
            payload.actionType(),
            payload.authorizationReference(),
            payload.toolExecutionId(),
            payload.toolResultId(),
            payload.completedAt(),
            payload.resultSummary(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
