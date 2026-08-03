package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class ResolveTicketApiMapper {

    public ResolveTicketCommand toCommand(
        TicketId ticketId, ResolveTicketRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ResolveTicketCommand(
            ticketId,
            request.resolutionCode(),
            request.resolutionSummary().trim(),
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public ResolveTicketResponse toResponse(ResolveTicketResult result) {
        return new ResolveTicketResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.resolutionCode().name(),
            result.resolutionSummary(),
            result.resolvedBy(),
            result.resolvedAt(),
            result.resolutionCycleId(),
            result.autoCloseDueAt(),
            result.version()
        );
    }
}
