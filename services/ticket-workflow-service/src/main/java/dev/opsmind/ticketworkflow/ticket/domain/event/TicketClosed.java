package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** SPEC-TW-011 §2: {@code RESOLVED -> CLOSED} (transitionId {@code SM-011}, reasonCode {@code TICKET_CLOSED}). */
public record TicketClosed(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID resolutionCycleId,
    CloseReasonCode closeReasonCode,
    String closeReason,
    String closedByType,
    String closedById,
    Instant closedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketClosed {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(closeReasonCode, "closeReasonCode must not be null");
        Objects.requireNonNull(closeReason, "closeReason must not be null");
        Objects.requireNonNull(closedByType, "closedByType must not be null");
        Objects.requireNonNull(closedById, "closedById must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
