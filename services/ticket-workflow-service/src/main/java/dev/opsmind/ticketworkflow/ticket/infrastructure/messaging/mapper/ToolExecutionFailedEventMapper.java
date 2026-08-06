package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ToolExecutionFailedEventPayload;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ToolExecutionFailedEventMapper {

    public ApplyToolExecutionFailedCommand toCommand(ConsumedEventEnvelope envelope, ToolExecutionFailedEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyToolExecutionFailedCommand(
            ticketId,
            envelope.eventId(),
            payload.workflowId(),
            payload.actionId(),
            payload.actionType(),
            payload.authorizationReference(),
            payload.toolExecutionId(),
            payload.failureCode(),
            payload.failureClass(),
            payload.failedAt(),
            payload.retryable(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
