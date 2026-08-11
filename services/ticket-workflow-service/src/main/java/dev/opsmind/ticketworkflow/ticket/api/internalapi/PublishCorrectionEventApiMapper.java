package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PublishCorrectionEventApiMapper {

    public PublishCorrectionEventCommand toCommand(
        TicketId ticketId, PublishCorrectionEventRequest request, ActorContext actor,
        String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new PublishCorrectionEventCommand(
            ticketId,
            request.reasonCode(),
            request.reason().trim(),
            request.sourceReference().trim(),
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public PublishCorrectionEventResponse toResponse(PublishCorrectionEventResult result) {
        return new PublishCorrectionEventResponse(
            result.decision().name(),
            result.recoveryId(),
            result.eventName()
        );
    }
}
