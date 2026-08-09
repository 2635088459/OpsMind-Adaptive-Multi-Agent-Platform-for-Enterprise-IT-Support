package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-032 domain-rules: {@code ESCALATED -> IN_PROGRESS}. {@code
 * ownershipStatus} mirrors {@link TicketReopened}'s (SPEC-TW-011) same
 * field — Resume, like Reopen, never silently reassigns; it only reports
 * whether the ticket's existing owner (if any) is still active.
 */
public record TicketEscalationResumed(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String teamId,
    SupportQueueId supportQueueId,
    String assigneeId,
    UUID resolutionCycleId,
    EscalationResumeReasonCode resumeReasonCode,
    String resumeReason,
    String resumedByType,
    String resumedById,
    Instant resumedAt,
    OwnershipStatus ownershipStatus,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketEscalationResumed {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(resumeReasonCode, "resumeReasonCode must not be null");
        Objects.requireNonNull(resumeReason, "resumeReason must not be null");
        Objects.requireNonNull(resumedByType, "resumedByType must not be null");
        Objects.requireNonNull(resumedById, "resumedById must not be null");
        Objects.requireNonNull(resumedAt, "resumedAt must not be null");
        Objects.requireNonNull(ownershipStatus, "ownershipStatus must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
