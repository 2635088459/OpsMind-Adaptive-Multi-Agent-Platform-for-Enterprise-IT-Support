package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ReplayEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReplayEventResult;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ReplayEventApiMapper {

    public ReplayEventCommand toCommand(
        ReplayEventRequest request, ActorContext actor,
        String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ReplayEventCommand(
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

    public ReplayEventResponse toResponse(ReplayEventResult result) {
        return new ReplayEventResponse(
            result.decision().name(),
            result.recoveryId(),
            result.eventName()
        );
    }
}
