package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class CloseTicketApiMapper {

    public CloseTicketCommand toCommand(
        TicketId ticketId, CloseTicketRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new CloseTicketCommand(
            ticketId,
            request.closeReasonCode(),
            request.closeReason().trim(),
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public CloseTicketResponse toResponse(CloseTicketResult result) {
        return new CloseTicketResponse(
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
