package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when SPEC-TW-035 Secret Detection policy denies a request: the
 * evaluated free text matched a secret/credential pattern. Maps to {@code
 * 403 FORBIDDEN} and never reveals the matched pattern, the evaluated
 * {@link #decisionCode()}, or any part of the offending text to the client
 * (SPEC-TW-004 §7: "the rejection message never echoes the matched secret
 * text").
 */
public class SecretDetectionPolicyDeniedException extends RuntimeException {

    private final String decisionCode;

    public SecretDetectionPolicyDeniedException(String decisionCode) {
        super("content must not contain secrets or credentials");
        this.decisionCode = decisionCode;
    }

    public String decisionCode() {
        return decisionCode;
    }
}
