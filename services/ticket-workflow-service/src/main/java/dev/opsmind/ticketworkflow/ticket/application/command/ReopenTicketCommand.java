package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Set;

public record ReopenTicketCommand(
    TicketId ticketId,
    ReopenReasonCode reopenReasonCode,
    String reopenReason,
    long expectedVersion,
    ActorContext actor,
    Set<String> allowedTeamIds,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
