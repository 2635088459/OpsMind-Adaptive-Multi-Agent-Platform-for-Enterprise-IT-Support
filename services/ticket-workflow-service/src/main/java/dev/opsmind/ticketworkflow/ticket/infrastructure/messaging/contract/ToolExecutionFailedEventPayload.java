package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** SPEC-TW-020 event-contract / 06-event-contracts CON-011 {@code tool.execution.failed} payload shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolExecutionFailedEventPayload(
    String workflowId,
    String actionId,
    String actionType,
    String authorizationReference,
    String toolExecutionId,
    String failureCode,
    String failureClass,
    Boolean retryable,
    Instant failedAt
) {
}
