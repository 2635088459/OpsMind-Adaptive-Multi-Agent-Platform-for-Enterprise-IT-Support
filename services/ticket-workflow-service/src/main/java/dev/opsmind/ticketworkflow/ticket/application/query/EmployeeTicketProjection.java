package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only JDBC projection for the Employee Get Ticket view (SPEC-TW-002
 * §12). Deliberately excludes requesterId, internal assignment, and audit
 * metadata so the projection query itself cannot leak internal fields.
 */
public record EmployeeTicketProjection(
    UUID ticketId,
    String displayId,
    String title,
    String description,
    String applicationCode,
    String source,
    String status,
    String priority,
    Instant createdAt,
    Instant updatedAt,
    long version,
    String slaState,
    Instant slaResponseDueAt,
    Instant slaResolutionDueAt
) {
}
