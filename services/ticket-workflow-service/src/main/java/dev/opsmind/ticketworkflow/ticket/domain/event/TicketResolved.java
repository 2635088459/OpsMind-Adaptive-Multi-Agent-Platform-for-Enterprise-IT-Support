package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** SPEC-TW-010 §10: {@code IN_PROGRESS -> RESOLVED} (transitionId {@code SM-010}, reasonCode {@code TICKET_RESOLVED}). */
public record TicketResolved(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID resolutionCycleId,
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

    public TicketResolved {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
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
