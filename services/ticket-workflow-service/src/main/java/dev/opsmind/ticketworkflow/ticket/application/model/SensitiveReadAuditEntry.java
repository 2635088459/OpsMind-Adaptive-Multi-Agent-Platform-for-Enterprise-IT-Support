package dev.opsmind.ticketworkflow.ticket.application.model;

import java.time.Instant;

/**
 * Required audit entry for a Support (or future Auditor) sensitive Ticket
 * read (SPEC-TW-002 §16). Deliberately excludes Title, Description, response
 * body, JWT, and raw scopes. {@code viewType} is the resolved view's enum
 * name (e.g. {@code TicketViewType.SUPPORT_VIEW.name()} or {@code
 * TicketTimelineViewType.SUPPORT_INTERNAL_VIEW.name()}) rather than a
 * feature-specific enum type, so this one record and {@link
 * dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort}
 * are shared across every feature that needs a required sensitive-read
 * audit, instead of each feature duplicating its own entry/port/adapter
 * triplet (SPEC-TW-006 reuses this as-is).
 */
public record SensitiveReadAuditEntry(
    String actorType,
    String actorId,
    String clientId,
    String resourceId,
    String viewType,
    String fieldsPolicyVersion,
    String traceId,
    String outcome,
    Instant occurredAt
) {
}
