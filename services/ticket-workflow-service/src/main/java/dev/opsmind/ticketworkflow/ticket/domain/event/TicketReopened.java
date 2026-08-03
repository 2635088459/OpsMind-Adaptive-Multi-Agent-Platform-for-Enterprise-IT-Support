package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-011 §3: {@code RESOLVED -> IN_PROGRESS} (transitionId {@code
 * SM-012}) or {@code CLOSED -> IN_PROGRESS} (transitionId {@code SM-013}),
 * both reasonCode {@code TICKET_REOPENED}. {@code assigneeId} is nullable —
 * reopen never assigns, so a ticket can reach this event unassigned.
 */
public record TicketReopened(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID previousResolutionCycleId,
    UUID newResolutionCycleId,
    int newResolutionCycleNumber,
    ReopenReasonCode reopenReasonCode,
    String reopenReason,
    String reopenedByType,
    String reopenedById,
    Instant reopenedAt,
    int reopenCount,
    OwnershipStatus ownershipStatus,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketReopened {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(previousResolutionCycleId, "previousResolutionCycleId must not be null");
        Objects.requireNonNull(newResolutionCycleId, "newResolutionCycleId must not be null");
        Objects.requireNonNull(reopenReasonCode, "reopenReasonCode must not be null");
        Objects.requireNonNull(reopenReason, "reopenReason must not be null");
        Objects.requireNonNull(reopenedByType, "reopenedByType must not be null");
        Objects.requireNonNull(reopenedById, "reopenedById must not be null");
        Objects.requireNonNull(reopenedAt, "reopenedAt must not be null");
        Objects.requireNonNull(ownershipStatus, "ownershipStatus must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (reopenCount < 1) {
            throw new IllegalArgumentException("reopenCount must be >= 1");
        }
    }
}
