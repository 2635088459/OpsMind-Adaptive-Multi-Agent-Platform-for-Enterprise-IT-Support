package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a Ticket is CLOSED or CANCELLED (SPEC-TW-004 §8). The
 * message never includes the Ticket's current status so it stays safe to
 * log at any level.
 */
public class TicketMessageNotAllowedInStateException extends RuntimeException {

    public TicketMessageNotAllowedInStateException() {
        super("a message cannot be added while the Ticket is in its current state");
    }
}
