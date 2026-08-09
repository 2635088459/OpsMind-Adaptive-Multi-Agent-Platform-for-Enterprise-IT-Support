package dev.opsmind.ticketworkflow.ticket.api.internalapi;

/** SPEC-TW-035 api-contract §"Response 200". */
public record SecretDetectionEvaluateResponse(
    String decision,
    String decisionCode,
    boolean auditRequired
) {
}
