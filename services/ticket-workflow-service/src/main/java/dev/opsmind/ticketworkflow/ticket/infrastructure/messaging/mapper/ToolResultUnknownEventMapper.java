package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ToolResultUnknownEventPayload;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ToolResultUnknownEventMapper {

    public ApplyToolResultUnknownCommand toCommand(ConsumedEventEnvelope envelope, ToolResultUnknownEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyToolResultUnknownCommand(
            ticketId,
            envelope.eventId(),
            payload.workflowId(),
            payload.actionId(),
            payload.actionType(),
            payload.authorizationReference(),
            payload.toolExecutionId(),
            payload.unknownReason(),
            payload.evidenceReferences(),
            payload.observedAt(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
