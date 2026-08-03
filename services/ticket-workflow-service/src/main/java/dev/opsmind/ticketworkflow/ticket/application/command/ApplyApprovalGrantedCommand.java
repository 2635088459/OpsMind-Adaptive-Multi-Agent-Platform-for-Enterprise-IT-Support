package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/** Mapped from a trusted, schema- and producer-validated {@code approval.granted.v1} event by the messaging infrastructure layer. */
public record ApplyApprovalGrantedCommand(
    TicketId ticketId,
    String eventId,
    String workflowId,
    String actionId,
    String actionType,
    String approvalId,
    String approvedByHash,
    Instant approvedAt,
    Instant expiresAt,
    String traceId,
    String correlationId
) {
}
