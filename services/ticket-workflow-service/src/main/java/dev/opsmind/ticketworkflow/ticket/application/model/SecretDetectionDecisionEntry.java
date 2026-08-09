package dev.opsmind.ticketworkflow.ticket.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single Secret Detection policy decision (SPEC-TW-035 event-contract,
 * {@code security.secret-detected}). Every decision is written here —
 * {@code ALLOW}, {@code DENY}, and {@code FAIL_CLOSED} alike (event-contract's
 * own example shows an {@code ALLOW} decision). Deliberately excludes the
 * evaluated free text and the matched pattern itself: only opaque
 * identifiers, the low-cardinality {@code decisionCode} (which encodes the
 * matched {@code SecretPatternCategory} when denied), and the resolved
 * decision are persisted — never the raw or redacted secret (domain-rules:
 * "never persisted raw").
 */
public record SecretDetectionDecisionEntry(
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
