package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record RequestUserInputResult(
    TicketId ticketId,
    UUID requestId,
    TicketStatus previousStatus,
    TicketStatus status,
    String prompt,
    String requestedBy,
    Instant requestedAt,
    Instant waitingForRequesterSince,
    long version,
    boolean replayed
) {
}
