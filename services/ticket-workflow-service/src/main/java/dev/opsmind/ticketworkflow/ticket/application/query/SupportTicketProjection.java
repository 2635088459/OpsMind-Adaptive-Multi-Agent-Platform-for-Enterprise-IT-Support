package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only JDBC projection for the Support Get Ticket view (SPEC-TW-002
 * §13). {@code requesterId} carries the raw internal identifier only within
 * the trust boundary of the application layer; the API mapper pseudonymizes
 * it before it reaches the response body (LLD 11-security §15).
 */
public record SupportTicketProjection(
    UUID ticketId,
    String displayId,
    String title,
    String description,
    String applicationCode,
    String source,
    String status,
    String priority,
    String requesterId,
    String currentTeamId,
    String currentSupportUserId,
    Integer resolutionCycleNumber,
    String resolutionCycleStatus,
    String slaState,
    String slaPolicyId,
    Instant slaResponseDueAt,
    Instant slaResolutionDueAt,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
}
