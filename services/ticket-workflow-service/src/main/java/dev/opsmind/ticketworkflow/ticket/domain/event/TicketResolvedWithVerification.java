package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-025 domain-rules §1: {@code VERIFYING -> RESOLVED} (transitionId
 * {@code SM-030}, reasonCode {@code VERIFIED_RESOLUTION}). Unlike {@link
 * TicketResolved} (SPEC-TW-010's human-judgment {@code IN_PROGRESS ->
 * RESOLVED}), this transition requires trusted verification evidence
 * (SPEC-TW-023) bound to the ticket's current resolution cycle — {@code
 * verificationId}/{@code verificationEvidenceId} are carried on the event so
 * every consumer of the resolution can trace exactly which verification
 * attempt justified it.
 */
public record TicketResolvedWithVerification(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID resolutionCycleId,
    String verificationId,
    String verificationEvidenceId,
    ResolutionCode resolutionCode,
    String resolutionSummary,
    String resolvedByType,
    String resolvedById,
    Instant resolvedAt,
    Instant autoCloseDueAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketResolvedWithVerification {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(verificationEvidenceId, "verificationEvidenceId must not be null");
        Objects.requireNonNull(resolutionCode, "resolutionCode must not be null");
        Objects.requireNonNull(resolutionSummary, "resolutionSummary must not be null");
        Objects.requireNonNull(resolvedByType, "resolvedByType must not be null");
        Objects.requireNonNull(resolvedById, "resolvedById must not be null");
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        Objects.requireNonNull(autoCloseDueAt, "autoCloseDueAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
