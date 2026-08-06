package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

public record TicketToolExecutionFailedUpdate(
    TicketId ticketId,
    long expectedVersion,
    TicketStatus newStatus,
    String workflowId,
    String actionId,
    String authorizationReference,
    String toolExecutionId,
    String failureCode,
    String failureClass,
    Instant failedAt,
    Boolean safeToRetry,
    String eventId,
    Instant updatedAt
) {
}
