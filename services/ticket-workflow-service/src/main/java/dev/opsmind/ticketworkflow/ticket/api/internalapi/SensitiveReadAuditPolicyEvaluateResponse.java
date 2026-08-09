package dev.opsmind.ticketworkflow.ticket.api.internalapi;

/** SPEC-TW-034 api-contract §"Response 200". */
public record SensitiveReadAuditPolicyEvaluateResponse(
    String decision,
    String decisionCode,
    boolean auditRequired
) {
}
