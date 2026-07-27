package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only JDBC projection row for List Requester Tickets (SPEC-TW-003
 * §10). Deliberately excludes Description, requesterId, and internal
 * fields so the projection query itself cannot leak them.
 */
public record RequesterTicketSummary(
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
