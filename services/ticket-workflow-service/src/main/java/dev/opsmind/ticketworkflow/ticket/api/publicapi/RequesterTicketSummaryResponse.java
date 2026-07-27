package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches {@code schemas/ticket-summary.schema.json} (SPEC-TW-003 §10).
 * Never includes Description, requesterId, or internal fields.
 */
public record RequesterTicketSummaryResponse(
    UUID ticketId,
    String displayId,
    String title,
    String applicationCode,
    String status,
    String priority,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
}
