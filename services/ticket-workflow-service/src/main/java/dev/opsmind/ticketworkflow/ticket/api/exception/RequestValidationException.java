package dev.opsmind.ticketworkflow.ticket.api.exception;

/**
 * Raised for request-shape problems that Bean Validation does not cover on
 * its own: a missing/malformed {@code Idempotency-Key} header, or a field
 * value that is syntactically valid but not allowed for this actor (e.g.
 * {@code source} other than {@code PORTAL} for an Employee).
 */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message);
    }
}
