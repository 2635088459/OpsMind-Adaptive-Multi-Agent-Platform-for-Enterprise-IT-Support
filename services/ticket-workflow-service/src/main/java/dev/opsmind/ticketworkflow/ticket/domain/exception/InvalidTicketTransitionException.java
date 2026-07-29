package dev.opsmind.ticketworkflow.ticket.domain.exception;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/**
 * Raised by an aggregate transition rule (SPEC-TW-007 §3) when the
 * Ticket's current status is not the one the transition requires. Unlike
 * {@link dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException}
 * this deliberately DOES expose {@link #currentStatus()}/{@link
 * #requiredStatus()}: AC-08 requires the client to see both values, and
 * the actor invoking a state-changing command on a Ticket it can already
 * resource-authorize itself against is not learning anything new about
 * the Ticket's existence.
 */
public class InvalidTicketTransitionException extends RuntimeException {

    private final TicketStatus currentStatus;
    private final TicketStatus requiredStatus;

    public InvalidTicketTransitionException(TicketStatus currentStatus, TicketStatus requiredStatus) {
        super("ticket status " + currentStatus + " does not satisfy required status " + requiredStatus);
        this.currentStatus = currentStatus;
        this.requiredStatus = requiredStatus;
    }

    public TicketStatus currentStatus() {
        return currentStatus;
    }

    public TicketStatus requiredStatus() {
        return requiredStatus;
    }
}
