package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Set;

public record TransitionTicketStatusCommand(
    TicketId ticketId,
    TicketStatus targetStatus,
    String reason,
    Instant waitingForRequesterSince,
    String approvalReference,
    long expectedVersion,
    ActorContext actor,
    Set<String> allowedTeamIds,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
