package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;

/** Mapped from a trusted, schema- and producer-validated {@code tool.execution.completed.v1} event by the messaging infrastructure layer. */
public record ApplyToolExecutionCompletedCommand(
    TicketId ticketId,
    String eventId,
    String workflowId,
    String actionId,
    String actionType,
    String authorizationReference,
    String toolExecutionId,
    String toolResultId,
    Instant completedAt,
    Map<String, Object> resultSummary,
    String traceId,
    String correlationId
) {
}
