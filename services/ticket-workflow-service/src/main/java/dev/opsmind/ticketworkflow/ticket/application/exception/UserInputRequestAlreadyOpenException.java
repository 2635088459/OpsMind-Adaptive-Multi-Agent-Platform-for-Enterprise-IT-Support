package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a ticket is already {@code WAITING_FOR_USER} (it therefore
 * already has an {@code OPEN} user input request) and a new Request User
 * Input command targets it (SPEC-TW-012 AC-02). Distinct from the generic
 * {@link dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException}
 * so the client sees the more specific stable error code.
 */
public class UserInputRequestAlreadyOpenException extends RuntimeException {

    public UserInputRequestAlreadyOpenException() {
        super("the ticket already has an open user input request");
    }
}
