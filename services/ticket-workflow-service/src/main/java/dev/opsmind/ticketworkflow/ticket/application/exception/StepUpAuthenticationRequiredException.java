package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when SPEC-TW-036 Step-up Authentication policy denies a
 * high-risk command or read: the step-up proof is missing, malformed, or
 * expired. Maps to {@code 403 FORBIDDEN} and never reveals the evaluated
 * {@link #decisionCode()} or any proof detail to the client.
 */
public class StepUpAuthenticationRequiredException extends RuntimeException {

    private final String decisionCode;

    public StepUpAuthenticationRequiredException(String decisionCode) {
        super("a valid step-up authentication proof is required for this operation");
        this.decisionCode = decisionCode;
    }

    public String decisionCode() {
        return decisionCode;
    }
}
