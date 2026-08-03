package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class ReopenTicketApiMapper {

    public ReopenTicketCommand toCommand(
        TicketId ticketId, ReopenTicketRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ReopenTicketCommand(
            ticketId,
            request.reopenReasonCode(),
            request.reopenReason().trim(),
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public ReopenTicketResponse toResponse(ReopenTicketResult result) {
        return new ReopenTicketResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.previousResolutionCycleId(),
            result.newResolutionCycleId(),
            result.reopenReasonCode().name(),
            result.reopenedBy(),
            result.reopenedAt(),
            result.reopenCount(),
            result.ownershipStatus().name(),
            result.version()
        );
    }
}
