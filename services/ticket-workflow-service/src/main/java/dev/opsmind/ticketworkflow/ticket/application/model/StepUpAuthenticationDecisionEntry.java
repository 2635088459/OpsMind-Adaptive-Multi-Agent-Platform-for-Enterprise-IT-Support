package dev.opsmind.ticketworkflow.ticket.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single Step-up Authentication policy decision (SPEC-TW-036
 * event-contract, {@code security.step-up-verified}). Every decision is
 * written here — {@code ALLOW}, {@code DENY}, and {@code FAIL_CLOSED} alike
 * (event-contract's own example shows an {@code ALLOW} decision).
 * Deliberately excludes the proof id, method, verifiedAt, and expiresAt
 * themselves — persistence_EN: "Step-up proof stores only proof id,
 * method, verifiedAt, and expiresAt, not authentication material" governs
 * the proof's own (out-of-scope-here) storage; this ledger only ever
 * records the resulting low-cardinality decision.
 */
public record StepUpAuthenticationDecisionEntry(
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
