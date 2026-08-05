package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/**
 * Mapped from a trusted, schema- and producer-validated {@code
 * approval.expired.v1} event by the messaging infrastructure layer. SPEC-TW-017
 * API contract: an internal scheduler evaluating local expiration may
 * construct and submit the same command shape directly, without going
 * through the event consumer (e.g. with a synthetic {@code eventId} and
 * {@code expirationReason}).
 */
public record ApplyApprovalExpiredCommand(
    TicketId ticketId,
    String eventId,
    String workflowId,
    String actionId,
    String approvalId,
    Instant expiredAt,
    String expirationReason,
    String traceId,
    String correlationId
) {
}
