package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * SPEC-TW-020 domain-rules §1: {@code EXECUTING -> IN_PROGRESS} (transitionId
 * {@code SM-022}, reasonCode {@code TOOL_EXECUTION_FAILED_SAFE}) for a
 * {@code KNOWN_SAFE}/{@code RETRYABLE_SAFE} failure, or {@code EXECUTING ->
 * FAILED} (transitionId {@code SM-023}, reasonCode {@code
 * TOOL_EXECUTION_PIPELINE_FAILED}) for a {@code PIPELINE_FAILED} one — see
 * {@link dev.opsmind.ticketworkflow.ticket.domain.model.Ticket#applyToolExecutionFailed}
 * for the classification. {@code UNKNOWN_SIDE_EFFECT} is never a valid
 * {@code failureClass} here — it belongs to SPEC-TW-021's separate {@code
 * tool.execution.result_unknown.v1} event/consumer.
 */
public record TicketToolExecutionFailedApplied(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    String workflowId,
    String actionId,
    String authorizationReference,
    String toolExecutionId,
    String failureCode,
    String failureClass,
    Instant failedAt,
    Boolean safeToRetry,
    String failedEventId,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketToolExecutionFailedApplied {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(toolExecutionId, "toolExecutionId must not be null");
        Objects.requireNonNull(failureCode, "failureCode must not be null");
        Objects.requireNonNull(failureClass, "failureClass must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        Objects.requireNonNull(failedEventId, "failedEventId must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
