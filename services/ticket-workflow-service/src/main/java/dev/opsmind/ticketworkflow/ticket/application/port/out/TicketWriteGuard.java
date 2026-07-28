package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/**
 * The minimal Ticket projection a write command needs to authorize and
 * state-guard itself against (SPEC-TW-004 §12 step 2) — not the full
 * aggregate.
 */
public record TicketWriteGuard(
    TicketId ticketId,
    String requesterId,
    ApplicationCode applicationCode,
    TicketStatus status
) {
}
