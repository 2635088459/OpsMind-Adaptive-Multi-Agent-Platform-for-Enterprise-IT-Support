package dev.opsmind.ticketworkflow.ticket.api.exception;

/** Raised when a mandatory {@code If-Match} header is missing (SPEC-TW-007 AC-10). */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("the If-Match header is required");
    }
}
