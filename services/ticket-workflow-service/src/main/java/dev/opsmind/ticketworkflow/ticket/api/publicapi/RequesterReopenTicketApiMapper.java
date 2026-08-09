package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.command.RequesterReopenTicketCommand;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RequesterReopenTicketApiMapper {

    public RequesterReopenTicketCommand toCommand(
        TicketId ticketId, RequesterReopenTicketRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new RequesterReopenTicketCommand(
            ticketId,
            request.reopenReasonCode(),
            request.reopenReason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public RequesterReopenTicketResponse toResponse(ReopenTicketResult result) {
        return new RequesterReopenTicketResponse(
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
