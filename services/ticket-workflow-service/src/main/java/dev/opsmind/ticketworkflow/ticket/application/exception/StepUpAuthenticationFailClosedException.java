package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a SPEC-TW-036 Step-up Authentication evaluation, or the
 * required decision-audit write backing it, fails unexpectedly.
 * Domain-rules: "Fail-closed behavior cannot be bypassed by fallback,
 * retry, or partial response" — this always denies (maps to {@code 500
 * INTERNAL_ERROR}), it never falls back to an implicit allow.
 */
public class StepUpAuthenticationFailClosedException extends RuntimeException {

    public StepUpAuthenticationFailClosedException(Throwable cause) {
        super("Step-up Authentication policy failed closed", cause);
    }
}
