package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a SPEC-TW-033 Support Queue authorization request is
 * well-formed but the current context cannot be evaluated: an unrecognized
 * {@code operation}, or a queue-scoped actor type with no {@code
 * context.supportQueueId} supplied. Maps to {@code 409 CONFLICT} ("current
 * Ticket state/context is not allowed" — API contract) and never reveals
 * policy internals to the client.
 */
public class SupportQueueAuthorizationConflictException extends RuntimeException {

    private final String decisionCode;

    public SupportQueueAuthorizationConflictException(String decisionCode) {
        super("the request context does not support a Support Queue authorization decision");
        this.decisionCode = decisionCode;
    }

    public String decisionCode() {
        return decisionCode;
    }
}
