package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-013 §1: {@code WAITING_FOR_USER -> IN_PROGRESS} (transitionId
 * {@code SM-015}, reasonCode {@code USER_REPLIED}) — only produced when the
 * reply targets the ticket's current {@code OPEN} user input request. A
 * reply to a stale/non-open request is saved as a plain {@code
 * TicketMessageAdded} instead and never reaches this event.
 */
public record TicketUserInputResumed(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    UUID requestId,
    UUID messageId,
    String repliedByType,
    String repliedById,
    Instant repliedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketUserInputResumed {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(repliedByType, "repliedByType must not be null");
        Objects.requireNonNull(repliedById, "repliedById must not be null");
        Objects.requireNonNull(repliedAt, "repliedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
