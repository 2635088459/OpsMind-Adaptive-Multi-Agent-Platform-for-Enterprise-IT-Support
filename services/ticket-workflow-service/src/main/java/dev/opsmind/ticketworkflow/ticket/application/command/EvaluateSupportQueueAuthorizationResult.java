package dev.opsmind.ticketworkflow.ticket.application.command;

/**
 * SPEC-TW-033 API contract 200 response body. Only ever returned for an
 * {@code ALLOW} decision — {@code DENY} and {@code FAIL_CLOSED} are surfaced
 * as {@code 403}/{@code 409}/{@code 500} through the stable error envelope
 * instead (API contract §"Errors"), so a caller can never branch on a
 * {@code decision} field in a 200 body that actually means "denied".
 */
public record EvaluateSupportQueueAuthorizationResult(
    String decision,
    String decisionCode,
    boolean auditRequired
) {
}
