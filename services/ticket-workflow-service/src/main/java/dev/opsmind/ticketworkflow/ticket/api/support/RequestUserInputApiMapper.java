package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class RequestUserInputApiMapper {

    public RequestUserInputCommand toCommand(
        TicketId ticketId, RequestUserInputRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new RequestUserInputCommand(
            ticketId,
            request.prompt().trim(),
            request.requestedFields(),
            request.expiresAt(),
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public RequestUserInputResponse toResponse(RequestUserInputResult result) {
        return new RequestUserInputResponse(
            result.ticketId().value(),
            result.requestId(),
            result.previousStatus().name(),
            result.status().name(),
            result.prompt(),
            result.requestedBy(),
            result.requestedAt(),
            result.waitingForRequesterSince(),
            result.version()
        );
    }
}
