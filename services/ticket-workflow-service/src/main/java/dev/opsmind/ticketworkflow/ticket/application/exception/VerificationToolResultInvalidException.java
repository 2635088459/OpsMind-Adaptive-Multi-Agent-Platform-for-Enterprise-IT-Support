package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when {@code toolResultId} does not reference a {@code COMPLETED} Phase 06 tool result belonging to this ticket (SPEC-TW-022 acceptance-criteria: "Tool result mismatch with current workflow/cycle/action is rejected"). */
public class VerificationToolResultInvalidException extends RuntimeException {

    public VerificationToolResultInvalidException() {
        super("toolResultId does not reference a completed tool result for this ticket");
    }
}
