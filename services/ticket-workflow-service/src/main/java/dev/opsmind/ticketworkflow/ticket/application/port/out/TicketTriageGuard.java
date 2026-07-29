package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/**
 * The minimal Ticket projection Triage needs to state-guard and version-
 * check itself against (mirrors {@link TicketWriteGuard}, SPEC-TW-004
 * §12) — not the full aggregate. Triage authorization is queue-scoped, not
 * requester-scoped, so this guard carries no requester identity.
 */
public record TicketTriageGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version
) {
}
