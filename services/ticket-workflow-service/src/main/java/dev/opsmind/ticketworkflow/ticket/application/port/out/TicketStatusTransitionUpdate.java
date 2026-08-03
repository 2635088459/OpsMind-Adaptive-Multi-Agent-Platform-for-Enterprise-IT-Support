package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

public record TicketStatusTransitionUpdate(
    TicketId ticketId,
    long expectedVersion,
    TicketStatus expectedStatus,
    TicketStatus newStatus,
    Instant waitingForRequesterSince,
    String approvalReference,
    Instant updatedAt
) {
}
