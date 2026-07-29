package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;

/**
 * The three-column keyset boundary carried by a Timeline cursor
 * (SPEC-TW-006 §16): {@code occurredAt, itemTypeRank, itemId}. {@code
 * itemId} is a composite string (e.g. {@code "MESSAGE:<uuid>"}, §10), not
 * a raw UUID, so it is compared lexicographically as text.
 */
public record TicketTimelineKeysetPosition(
    Instant occurredAt,
    int itemTypeRank,
    String itemId
) {
}
