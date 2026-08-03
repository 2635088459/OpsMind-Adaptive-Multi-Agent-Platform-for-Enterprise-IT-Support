package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TicketApprovalRequestUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID approvalRequestId,
    String approvalId,
    String workflowId,
    String actionId,
    String actionType,
    String riskLevel,
    Map<String, Object> riskContext,
    String reason,
    String requestedByType,
    String requestedById,
    Instant requestedAt,
    String correlationId,
    Instant updatedAt
) {
}
