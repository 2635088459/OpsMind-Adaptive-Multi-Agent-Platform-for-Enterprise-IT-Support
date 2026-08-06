package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when {@code verificationEvidenceId} does not reference trusted,
 * current, successful verification evidence for this ticket (SPEC-TW-025
 * acceptance-criteria: "Missing evidence returns 409 VERIFICATION_REQUIRED"
 * and "Old workflow/cycle/attempt evidence is rejected"). Both cases
 * collapse to the same outcome: the query that looks up evidence is already
 * scoped to the ticket's *current* resolution cycle, so a missing row, a row
 * for a different ticket, a row bound to a stale/old resolution cycle, and a
 * row that never reached {@code SUCCEEDED} are all, from the caller's
 * perspective, "no valid verification evidence found" — nothing here is
 * ever silently treated as good enough to resolve the ticket.
 */
public class VerificationEvidenceRequiredException extends RuntimeException {

    public VerificationEvidenceRequiredException() {
        super("verificationEvidenceId does not reference trusted, current, successful verification evidence for this ticket");
    }
}
