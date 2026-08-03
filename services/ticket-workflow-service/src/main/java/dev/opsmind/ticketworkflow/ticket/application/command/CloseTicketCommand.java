package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Set;

public record CloseTicketCommand(
    TicketId ticketId,
    CloseReasonCode closeReasonCode,
    String closeReason,
    long expectedVersion,
    ActorContext actor,
    Set<String> allowedTeamIds,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
