package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AutoCloseTicketApiMapper {

    public AutoCloseTicketCommand toCommand(
        TicketId ticketId, AutoCloseTicketRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new AutoCloseTicketCommand(
            ticketId,
            request.reason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public AutoCloseTicketResponse toResponse(AutoCloseTicketResult result) {
        return new AutoCloseTicketResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.closeReasonCode().name(),
            result.closedBy(),
            result.closedAt(),
            result.resolutionCycleId(),
            result.version()
        );
    }
}
