package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

public record CreateTicketResult(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    Instant createdAt,
    long version,
    boolean idempotencyReplayed
) {
}
