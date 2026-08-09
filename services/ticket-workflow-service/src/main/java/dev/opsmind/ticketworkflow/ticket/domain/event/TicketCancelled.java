package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-029 domain-rules: {@code non-terminal mutable state -> CANCELLED}
 * (one transitionId per source status — {@code SM-033} through {@code
 * SM-038}, see {@link dev.opsmind.ticketworkflow.ticket.domain.model.Ticket#CANCELLABLE_STATUSES}
 * — all sharing reasonCode {@code TICKET_CANCELLED}). {@code assigneeId} is
 * nullable — a ticket can be cancelled before it is ever assigned (e.g.
 * {@code NEW}).
 */
public record TicketCancelled(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID resolutionCycleId,
    CancelReasonCode cancelReasonCode,
    String cancelReason,
    String cancelledByType,
    String cancelledById,
    Instant cancelledAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketCancelled {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(cancelReasonCode, "cancelReasonCode must not be null");
        Objects.requireNonNull(cancelReason, "cancelReason must not be null");
        Objects.requireNonNull(cancelledByType, "cancelledByType must not be null");
        Objects.requireNonNull(cancelledById, "cancelledById must not be null");
        Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
