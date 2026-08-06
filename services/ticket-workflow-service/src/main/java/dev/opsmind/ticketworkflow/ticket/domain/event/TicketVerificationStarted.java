package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-022 domain-rules §1: {@code VERIFYING -> VERIFYING} (transitionId
 * {@code SM-025}, reasonCode {@code VERIFICATION_STARTED}) — a real,
 * recorded self-transition (its own status-history row and version bump),
 * mirroring {@link TicketAutoApprovalApplied}'s {@code IN_PROGRESS ->
 * IN_PROGRESS} shape (SPEC-TW-018): starting an independent verification
 * attempt from a Phase 06 tool result is a real business event even though
 * the ticket's own status does not change.
 */
public record TicketVerificationStarted(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    String verificationId,
    UUID resolutionCycleId,
    String workflowId,
    String toolResultId,
    int attemptNumber,
    String verificationType,
    String reason,
    String startedByType,
    String startedById,
    Instant startedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketVerificationStarted {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(toolResultId, "toolResultId must not be null");
        Objects.requireNonNull(verificationType, "verificationType must not be null");
        Objects.requireNonNull(startedByType, "startedByType must not be null");
        Objects.requireNonNull(startedById, "startedById must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
    }
}
