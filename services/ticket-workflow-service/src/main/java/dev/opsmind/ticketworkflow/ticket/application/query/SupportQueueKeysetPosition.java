package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.UUID;

/**
 * The four-column keyset boundary carried by a Support Queue cursor
 * (SPEC-TW-005 §15): {@code slaRank, priorityRank, createdAt, ticketId}.
 * {@code null} means "first page" (no boundary yet).
 */
public record SupportQueueKeysetPosition(
    int slaRank,
    int priorityRank,
    Instant createdAt,
    UUID ticketId
) {
}
