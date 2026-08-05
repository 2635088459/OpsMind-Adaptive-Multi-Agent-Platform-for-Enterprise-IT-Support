package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/** Mapped from a trusted, schema- and producer-validated {@code approval.rejected.v1} event by the messaging infrastructure layer. */
public record ApplyApprovalRejectedCommand(
    TicketId ticketId,
    String eventId,
    String workflowId,
    String actionId,
    String approvalId,
    String rejectedByHash,
    Instant rejectedAt,
    String rejectionReason,
    String traceId,
    String correlationId
) {
}
