package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only JDBC projection row for the Support Queue (SPEC-TW-005 §17).
 * Doubles as both the infrastructure-layer row shape and the
 * application-layer query result item — mirroring the RequesterTicketSummary
 * precedent from SPEC-TW-003 rather than introducing a separate
 * infrastructure-only "SupportQueueProjection" type for an identical shape.
 * Deliberately excludes full Description, message/note content, requester
 * email, and any other field forbidden by §17. Carries the raw {@code
 * requesterId}, not a pseudonymized reference — the API mapper
 * pseudonymizes it into {@code requesterRef} at the response boundary,
 * exactly as Get Ticket's Support view already does (SPEC-TW-002), so the
 * same requester maps to the same reference on both endpoints.
 */
public record SupportTicketSummary(
    UUID ticketId,
    String displayId,
    String title,
    String applicationCode,
    String status,
    SupportQueuePriority priority,
    String requesterId,
    String teamId,
    String agentId,
    boolean unassigned,
    SlaQueueState slaState,
    Instant slaResponseDueAt,
    Instant slaResolutionDueAt,
    int slaUrgencyRank,
    int priorityRank,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
}
