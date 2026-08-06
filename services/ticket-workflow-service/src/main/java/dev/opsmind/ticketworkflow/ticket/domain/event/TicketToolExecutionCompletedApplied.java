package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * SPEC-TW-019 domain-rules §1: {@code EXECUTING -> VERIFYING} (transitionId
 * {@code SM-021}, reasonCode {@code TOOL_EXECUTION_COMPLETED}). Tool success
 * only means the Tool Gateway finished the operation, not that the
 * underlying issue is resolved — {@code newStatus} is always {@code
 * VERIFYING}, never {@code RESOLVED}; only Phase 07 Verification can move a
 * ticket to {@code RESOLVED}.
 */
public record TicketToolExecutionCompletedApplied(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    String workflowId,
    String actionId,
    String authorizationReference,
    String toolExecutionId,
    String toolResultId,
    Instant completedAt,
    Map<String, Object> resultSummary,
    String completedEventId,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketToolExecutionCompletedApplied {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(toolExecutionId, "toolExecutionId must not be null");
        Objects.requireNonNull(toolResultId, "toolResultId must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(completedEventId, "completedEventId must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
