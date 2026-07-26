package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a Ticket does not exist, or exists but is outside the caller's
 * authorized resource scope. Both cases are deliberately indistinguishable
 * to the caller (SPEC-TW-002 §10): the message never includes the requested
 * TicketId so it stays safe to log at any level.
 */
public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException() {
        super("Ticket not found or not authorized");
    }
}
