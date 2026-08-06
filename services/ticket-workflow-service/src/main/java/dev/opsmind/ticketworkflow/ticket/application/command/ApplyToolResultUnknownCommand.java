package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.List;

/** Mapped from a trusted, schema- and producer-validated {@code tool.execution.result_unknown.v1} event by the messaging infrastructure layer. */
public record ApplyToolResultUnknownCommand(
    TicketId ticketId,
    String eventId,
    String workflowId,
    String actionId,
    String actionType,
    String authorizationReference,
    String toolExecutionId,
    String unknownReason,
    List<String> evidenceReferences,
    Instant observedAt,
    String traceId,
    String correlationId
) {
}
