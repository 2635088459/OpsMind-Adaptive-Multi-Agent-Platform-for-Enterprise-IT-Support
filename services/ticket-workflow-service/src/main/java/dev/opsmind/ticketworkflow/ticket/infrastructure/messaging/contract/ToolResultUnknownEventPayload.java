package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/** SPEC-TW-021 event-contract / 06-event-contracts CON-012 {@code tool.execution.result_unknown} payload shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolResultUnknownEventPayload(
    String workflowId,
    String actionId,
    String actionType,
    String authorizationReference,
    String toolExecutionId,
    String unknownReason,
    List<String> evidenceReferences,
    Instant observedAt
) {
}
