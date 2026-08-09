package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a SPEC-TW-036 Step-up Authentication policy request is
 * well-formed but its {@code operation} is not one this policy governs.
 * Maps to {@code 409 CONFLICT} ("current Ticket state/context is not
 * allowed" — API contract) and never reveals policy internals to the
 * client.
 */
public class StepUpAuthenticationPolicyConflictException extends RuntimeException {

    private final String decisionCode;

    public StepUpAuthenticationPolicyConflictException(String decisionCode) {
        super("the request operation is not governed by the Step-up Authentication policy");
        this.decisionCode = decisionCode;
    }

    public String decisionCode() {
        return decisionCode;
    }
}
