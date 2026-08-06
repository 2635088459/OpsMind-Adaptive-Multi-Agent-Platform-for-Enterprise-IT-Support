package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-023 domain-rules §1: {@code VERIFYING -> VERIFYING} (transitionId
 * {@code SM-026}, reasonCode {@code VERIFICATION_SUCCEEDED}) — a real,
 * recorded self-transition mirroring {@link
 * dev.opsmind.ticketworkflow.ticket.domain.model.Ticket#startVerification}'s
 * shape (SPEC-TW-022): successful verification is the terminal state for
 * the current active attempt and marks the ticket resolution-ready, but
 * does not itself resolve it — only SPEC-TW-025, given this trusted
 * evidence, may move the ticket to {@code RESOLVED}.
 */
public record TicketVerificationSuccessApplied(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    String verificationId,
    String workflowId,
    UUID resolutionCycleId,
    int attemptNumber,
    String verificationEvidenceId,
    Map<String, Object> evidenceSummary,
    Instant completedAt,
    String completedEventId,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketVerificationSuccessApplied {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(verificationEvidenceId, "verificationEvidenceId must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(completedEventId, "completedEventId must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
    }
}
