package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.VerificationSuccessEventPayload;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class VerificationSuccessEventMapper {

    public ApplyVerificationSuccessCommand toCommand(ConsumedEventEnvelope envelope, VerificationSuccessEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyVerificationSuccessCommand(
            ticketId,
            envelope.eventId(),
            payload.verificationId(),
            payload.workflowId(),
            payload.resolutionCycleId(),
            payload.attemptNumber(),
            payload.verificationEvidenceId(),
            payload.evidenceSummary(),
            payload.completedAt(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
