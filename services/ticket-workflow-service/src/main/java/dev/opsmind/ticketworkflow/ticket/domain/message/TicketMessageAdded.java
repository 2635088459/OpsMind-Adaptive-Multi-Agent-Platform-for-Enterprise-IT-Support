package dev.opsmind.ticketworkflow.ticket.domain.message;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Objects;

/**
 * Carries identity, type, visibility, author type, and creation time —
 * never the full message content (SPEC-TW-004 §9, §15).
 */
public record TicketMessageAdded(
    TicketMessageId messageId,
    TicketId ticketId,
    TicketMessageType messageType,
    MessageVisibility visibility,
    String authorType,
    Instant createdAt
) {

    public TicketMessageAdded {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(authorType, "authorType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
