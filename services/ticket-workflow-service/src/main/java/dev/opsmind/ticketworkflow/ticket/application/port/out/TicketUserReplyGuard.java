package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.UserInputRequestStatus;

/**
 * SPEC-TW-013: the ticket row joined with the specific user-input-request
 * row named in the URL (not necessarily the ticket's current {@code OPEN}
 * one — that distinction is exactly what {@link #requestStatus()} lets the
 * Application layer decide). {@code requestExistsForTicket} is {@code
 * false} both when the request id does not exist at all and when it exists
 * but belongs to a different ticket — both cases are deliberately
 * indistinguishable to the caller, same reasoning as {@code
 * TicketNotFoundException}.
 */
public record TicketUserReplyGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    String requesterId,
    TicketStatus status,
    long version,
    boolean requestExistsForTicket,
    UserInputRequestStatus requestStatus
) {
}
