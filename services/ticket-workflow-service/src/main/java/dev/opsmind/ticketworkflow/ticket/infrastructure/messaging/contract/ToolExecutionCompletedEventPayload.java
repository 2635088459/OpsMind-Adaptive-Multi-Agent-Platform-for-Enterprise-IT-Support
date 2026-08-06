package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/** SPEC-TW-019 event-contract / 06-event-contracts CON-010 {@code tool.execution.completed} payload shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolExecutionCompletedEventPayload(
    String workflowId,
    String actionId,
    String actionType,
    String authorizationReference,
    String toolExecutionId,
    String toolResultId,
    Instant completedAt,
    Map<String, Object> resultSummary
) {
}
