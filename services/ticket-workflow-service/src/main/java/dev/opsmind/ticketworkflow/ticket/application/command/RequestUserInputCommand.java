package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record RequestUserInputCommand(
    TicketId ticketId,
    String prompt,
    List<String> requestedFields,
    Instant expiresAt,
    long expectedVersion,
    ActorContext actor,
    Set<String> allowedTeamIds,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {

    public RequestUserInputCommand {
        requestedFields = requestedFields == null ? List.of() : List.copyOf(requestedFields);
    }
}
