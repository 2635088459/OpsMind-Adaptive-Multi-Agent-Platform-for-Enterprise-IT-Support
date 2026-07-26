package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;

import java.time.Instant;

/**
 * Required audit entry for a Support (or future Auditor) sensitive Ticket
 * read (SPEC-TW-002 §16). Deliberately excludes Title, Description, response
 * body, JWT, and raw scopes.
 */
public record SensitiveReadAuditEntry(
    String actorType,
    String actorId,
    String clientId,
    String resourceId,
    TicketViewType viewType,
    String fieldsPolicyVersion,
    String traceId,
    String outcome,
    Instant occurredAt
) {
}
