package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * SPEC-TW-021 domain-rules §1: {@code EXECUTING -> ESCALATED} (transitionId
 * {@code SM-024}, reasonCode {@code TOOL_RESULT_UNKNOWN}). An unknown result
 * is a safety boundary: the tool may or may not have produced an external
 * side effect, so the ticket can never silently return to {@code
 * IN_PROGRESS} and retry — it always escalates for manual investigation /
 * reconciliation before anything else touches this execution attempt.
 */
public record TicketToolResultUnknownRecorded(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    String workflowId,
    String actionId,
    String authorizationReference,
    String toolExecutionId,
    String unknownReason,
    List<String> evidenceReferences,
    Instant observedAt,
    boolean reconciliationRequired,
    String recordedEventId,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketToolResultUnknownRecorded {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(toolExecutionId, "toolExecutionId must not be null");
        Objects.requireNonNull(unknownReason, "unknownReason must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(recordedEventId, "recordedEventId must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
    }
}
