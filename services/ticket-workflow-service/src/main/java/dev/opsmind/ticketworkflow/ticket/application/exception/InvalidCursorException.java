package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised for any cursor problem: malformed, tampered, expired, wrong
 * version, or bound to different filters/sort/actor/operation than the
 * current request. Deliberately a single exception type with no detail
 * field — SPEC-TW-003 §15 requires that cursor errors never expose the
 * internal payload or the specific cryptographic failure reason.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("cursor is invalid or expired");
    }
}
