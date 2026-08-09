package dev.opsmind.ticketworkflow.ticket.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single Support Queue authorization policy decision (SPEC-TW-033
 * event-contract, {@code audit.authorization-denied-recorded}). Despite the
 * record name, every decision is written here — {@code ALLOW}, {@code DENY},
 * and {@code FAIL_CLOSED} alike (event-contract's own example shows an
 * {@code ALLOW} decision) — so the ledger stays a complete, traceable record
 * of every policy evaluation, not only rejections. Deliberately excludes the
 * actor's full authorized scope, JWT claims, and any request/response body:
 * only opaque identifiers and the resolved decision are persisted.
 */
public record SupportQueueAuthorizationDecisionEntry(
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
