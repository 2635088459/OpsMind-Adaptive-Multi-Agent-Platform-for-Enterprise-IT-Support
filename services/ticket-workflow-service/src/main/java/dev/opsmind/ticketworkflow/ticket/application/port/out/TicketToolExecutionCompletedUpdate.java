package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;

public record TicketToolExecutionCompletedUpdate(
    TicketId ticketId,
    long expectedVersion,
    String workflowId,
    String actionId,
    String authorizationReference,
    String toolExecutionId,
    String toolResultId,
    Instant completedAt,
    Map<String, Object> resultSummary,
    String eventId,
    Instant updatedAt
) {
}
