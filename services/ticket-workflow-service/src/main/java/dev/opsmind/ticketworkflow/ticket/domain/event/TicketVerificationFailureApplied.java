package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-024 domain-rules §1: {@code VERIFYING -> IN_PROGRESS} (transitionId
 * {@code SM-027}, reasonCode {@code VERIFICATION_FAILED_RETRYABLE}) for a
 * retryable failure still under the failure limit; {@code VERIFYING ->
 * ESCALATED} (transitionId {@code SM-028}, reasonCode {@code
 * VERIFICATION_FAILED_LIMIT_OR_UNSAFE}) for an unsafe result or a
 * retryable failure that has reached the limit (the third failure); or
 * {@code VERIFYING -> FAILED} (transitionId {@code SM-029}, reasonCode
 * {@code VERIFICATION_PIPELINE_FAILED}) when the verification pipeline
 * itself failed — see {@link
 * dev.opsmind.ticketworkflow.ticket.domain.model.Ticket#applyVerificationFailure}
 * for the classification.
 */
public record TicketVerificationFailureApplied(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    String verificationId,
    String workflowId,
    UUID resolutionCycleId,
    int attemptNumber,
    String failureCode,
    String failureClass,
    boolean unsafeResult,
    Instant failedAt,
    String failedEventId,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketVerificationFailureApplied {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(failureCode, "failureCode must not be null");
        Objects.requireNonNull(failureClass, "failureClass must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        Objects.requireNonNull(failedEventId, "failedEventId must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
    }
}
