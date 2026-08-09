package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a SPEC-TW-035 Secret Detection policy request is well-formed
 * but its {@code operation} is not one this policy governs. Maps to {@code
 * 409 CONFLICT} ("current Ticket state/context is not allowed" — API
 * contract) and never reveals policy internals to the client.
 */
public class SecretDetectionPolicyConflictException extends RuntimeException {

    private final String decisionCode;

    public SecretDetectionPolicyConflictException(String decisionCode) {
        super("the request operation is not governed by the Secret Detection policy");
        this.decisionCode = decisionCode;
    }

    public String decisionCode() {
        return decisionCode;
    }
}
