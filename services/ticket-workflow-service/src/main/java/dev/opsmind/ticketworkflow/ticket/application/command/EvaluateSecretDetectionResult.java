package dev.opsmind.ticketworkflow.ticket.application.command;

/**
 * SPEC-TW-035 API contract 200 response body. Only ever returned for an
 * {@code ALLOW} decision — {@code DENY} and {@code FAIL_CLOSED} are surfaced
 * as {@code 403}/{@code 409}/{@code 500} through the stable error envelope
 * instead (API contract §"Errors").
 */
public record EvaluateSecretDetectionResult(
    String decision,
    String decisionCode,
    boolean auditRequired
) {
}
