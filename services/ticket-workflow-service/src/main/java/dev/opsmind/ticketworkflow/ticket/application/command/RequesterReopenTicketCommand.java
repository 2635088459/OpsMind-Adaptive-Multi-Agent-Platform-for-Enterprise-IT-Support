package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/**
 * SPEC-TW-028: the requester/authorized-support counterpart of {@link
 * ReopenTicketCommand} (SPEC-TW-011) — no {@code allowedTeamIds}, since this
 * path never checks Support Queue membership. Both commands drive the exact
 * same domain transition ({@code Ticket.reopen(...)}) and the exact same
 * {@link ReopenTicketResult}/{@code ticket.reopened.v1} event; only the
 * authorization path and the HTTP entry point differ.
 */
public record RequesterReopenTicketCommand(
    TicketId ticketId,
    ReopenReasonCode reopenReasonCode,
    String reopenReason,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
