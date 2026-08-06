package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.VerificationFailureEventPayload;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class VerificationFailureEventMapper {

    public ApplyVerificationFailureCommand toCommand(ConsumedEventEnvelope envelope, VerificationFailureEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyVerificationFailureCommand(
            ticketId,
            envelope.eventId(),
            payload.verificationId(),
            payload.workflowId(),
            payload.resolutionCycleId(),
            payload.attemptNumber(),
            payload.failureCode(),
            payload.failureClass(),
            Boolean.TRUE.equals(payload.unsafe()),
            payload.failedAt(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
