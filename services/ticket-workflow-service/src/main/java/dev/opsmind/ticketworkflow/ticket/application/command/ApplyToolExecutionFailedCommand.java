package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/** Mapped from a trusted, schema- and producer-validated {@code tool.execution.failed.v1} event by the messaging infrastructure layer. */
public record ApplyToolExecutionFailedCommand(
    TicketId ticketId,
    String eventId,
    String workflowId,
    String actionId,
    String actionType,
    String authorizationReference,
    String toolExecutionId,
    String failureCode,
    String failureClass,
    Instant failedAt,
    Boolean retryable,
    String traceId,
    String correlationId
) {
}
