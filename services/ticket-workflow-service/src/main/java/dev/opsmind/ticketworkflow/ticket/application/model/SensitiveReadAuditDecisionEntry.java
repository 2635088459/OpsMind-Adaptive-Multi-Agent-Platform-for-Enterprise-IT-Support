package dev.opsmind.ticketworkflow.ticket.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single Sensitive Read Audit policy decision (SPEC-TW-034 event-contract,
 * {@code audit.sensitive-read-recorded}). Every decision is written here —
 * {@code ALLOW}, {@code DENY}, and {@code FAIL_CLOSED} alike (event-contract's
 * own example shows an {@code ALLOW} decision) — separate from the
 * pre-existing required {@code ticket.audit_records} {@code SENSITIVE_READ}
 * row that Get Ticket (SPEC-TW-002) and Ticket Timeline (SPEC-TW-006) already
 * write and fail closed on: that row is the business audit trail; this one is
 * the SPEC-TW-034 policy-decision trail. Deliberately excludes the actor's
 * scope, JWT claims, and any response body: only opaque identifiers and the
 * resolved decision are persisted.
 */
public record SensitiveReadAuditDecisionEntry(
    UUID id,
    String ticketId,
    String actorId,
    String actorType,
    String operation,
    String decision,
    String decisionCode,
    String correlationId,
    String traceId,
    Instant occurredAt
) {
}
