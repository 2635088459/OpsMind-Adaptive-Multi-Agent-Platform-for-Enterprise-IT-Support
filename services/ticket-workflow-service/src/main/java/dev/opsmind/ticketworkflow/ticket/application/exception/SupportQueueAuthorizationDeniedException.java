package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when SPEC-TW-033 Support Queue authorization policy denies a
 * request: the target actor type is not queue-scoped, or the target actor
 * is not a member of the requested Support Queue. Maps to {@code 403
 * FORBIDDEN} and never reveals the actor's authorized queues, {@link
 * #decisionCode()}, or the evaluated policy internals to the client.
 */
public class SupportQueueAuthorizationDeniedException extends RuntimeException {

    private final String decisionCode;

    public SupportQueueAuthorizationDeniedException(String decisionCode) {
        super("the actor is not authorized within the requested Support Queue scope");
        this.decisionCode = decisionCode;
    }

    public String decisionCode() {
        return decisionCode;
    }
}
