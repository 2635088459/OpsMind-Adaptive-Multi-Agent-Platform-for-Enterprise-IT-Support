package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when SPEC-TW-034 Sensitive Read Audit policy denies a request: the
 * target actor type is not eligible for any read view this policy governs.
 * Maps to {@code 403 FORBIDDEN} and never reveals the evaluated {@link
 * #decisionCode()} or the policy internals to the client.
 */
public class SensitiveReadAuditPolicyDeniedException extends RuntimeException {

    private final String decisionCode;

    public SensitiveReadAuditPolicyDeniedException(String decisionCode) {
        super("the actor is not eligible for a sensitive Ticket read");
        this.decisionCode = decisionCode;
    }

    public String decisionCode() {
        return decisionCode;
    }
}
