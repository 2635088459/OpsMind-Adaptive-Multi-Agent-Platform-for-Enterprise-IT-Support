package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a required sensitive-read audit insert fails. The Get Ticket
 * read fails closed: sensitive Ticket detail is never returned without the
 * required audit record (SPEC-TW-002 §16).
 */
public class SensitiveReadAuditFailureException extends RuntimeException {

    public SensitiveReadAuditFailureException(Throwable cause) {
        super("required sensitive-read audit failed", cause);
    }
}
