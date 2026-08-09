package dev.opsmind.ticketworkflow.ticket.api.internalapi;

/** SPEC-TW-033 api-contract §"Response 200". */
public record SupportQueueAuthorizationEvaluateResponse(
    String decision,
    String decisionCode,
    boolean auditRequired
) {
}
