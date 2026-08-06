package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when {@code toolResultId} already has an {@code ACTIVE} verification attempt (SPEC-TW-022 domain-rules §"no two active verification attempts for the same tool result"). */
public class VerificationAttemptAlreadyActiveException extends RuntimeException {

    public VerificationAttemptAlreadyActiveException() {
        super("the tool result already has an active verification attempt");
    }
}
