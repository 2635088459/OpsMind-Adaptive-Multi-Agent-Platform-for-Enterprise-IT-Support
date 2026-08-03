package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

public record TransitionTicketStatusResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    String reason,
    Instant waitingForRequesterSince,
    String approvalReference,
    Instant transitionedAt,
    long version,
    boolean replayed
) {
}
