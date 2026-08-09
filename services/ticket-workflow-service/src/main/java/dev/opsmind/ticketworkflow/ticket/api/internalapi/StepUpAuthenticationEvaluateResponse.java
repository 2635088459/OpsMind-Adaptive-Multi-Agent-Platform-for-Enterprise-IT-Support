package dev.opsmind.ticketworkflow.ticket.api.internalapi;

/** SPEC-TW-036 api-contract §"Response 200". */
public record StepUpAuthenticationEvaluateResponse(
    String decision,
    String decisionCode,
    boolean auditRequired
) {
}
