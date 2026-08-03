package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class TransitionTicketStatusApiMapper {

    public TransitionTicketStatusCommand toCommand(
        TicketId ticketId, TransitionTicketStatusRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new TransitionTicketStatusCommand(
            ticketId,
            request.targetStatus(),
            request.reason().trim(),
            request.waitingForRequesterSince(),
            request.approvalReference() == null ? null : request.approvalReference().trim(),
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public TransitionTicketStatusResponse toResponse(TransitionTicketStatusResult result) {
        return new TransitionTicketStatusResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.reason(),
            result.waitingForRequesterSince(),
            result.approvalReference(),
            result.transitionedAt(),
            result.version()
        );
    }
}
