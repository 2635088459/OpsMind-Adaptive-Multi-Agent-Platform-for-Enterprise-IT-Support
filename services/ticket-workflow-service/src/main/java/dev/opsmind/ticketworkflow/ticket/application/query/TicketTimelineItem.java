package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;

/**
 * Read-only JDBC projection row for one Timeline item (SPEC-TW-006 §10,
 * §18), doubling as both the infrastructure-layer row shape and the
 * application-layer item, mirroring the SupportTicketSummary precedent
 * from SPEC-TW-005. Deliberately raw and un-redacted: actor labeling,
 * pseudonymization, and per-view field suppression happen in the API
 * mapper layer (§19, §20), not here — this record carries everything the
 * SQL projection can supply for any view, and the mapper decides what a
 * given actor is allowed to see.
 */
public record TicketTimelineItem(
    String itemId,
    TicketTimelineItemType itemType,
    String visibility,
    Instant occurredAt,
    String actorType,
    String actorId,
    String fromStatus,
    String toStatus,
    String transitionId,
    String reasonCode,
    String messageType,
    String content,
    Long relatedVersion
) {
}
