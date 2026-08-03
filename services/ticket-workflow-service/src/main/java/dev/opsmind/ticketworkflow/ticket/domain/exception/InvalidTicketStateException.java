package dev.opsmind.ticketworkflow.ticket.domain.exception;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.Set;

/**
 * The multi-status counterpart to {@link InvalidTicketTransitionException}:
 * raised when a command accepts more than one legal source status (only
 * {@code Ticket.reassign(...)} does today — SPEC-TW-008 domain-rules §4
 * allows {@code ASSIGNED}/{@code WAITING_FOR_USER}/{@code
 * WAITING_FOR_APPROVAL}) and the ticket's current status is none of them.
 * Deliberately exposes {@link #currentStatus()}/{@link #allowedStatuses()},
 * same reasoning as {@link InvalidTicketTransitionException} (AC-08 wants
 * the client to see both).
 */
public class InvalidTicketStateException extends RuntimeException {

    private final TicketStatus currentStatus;
    private final Set<TicketStatus> allowedStatuses;

    public InvalidTicketStateException(TicketStatus currentStatus, Set<TicketStatus> allowedStatuses) {
        super("ticket status " + currentStatus + " is not one of the allowed statuses " + allowedStatuses);
        this.currentStatus = currentStatus;
        this.allowedStatuses = allowedStatuses;
    }

    public TicketStatus currentStatus() {
        return currentStatus;
    }

    public Set<TicketStatus> allowedStatuses() {
        return allowedStatuses;
    }
}
