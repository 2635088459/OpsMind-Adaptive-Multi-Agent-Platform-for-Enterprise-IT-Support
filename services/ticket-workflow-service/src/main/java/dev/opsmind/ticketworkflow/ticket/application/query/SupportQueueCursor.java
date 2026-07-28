package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded Support Queue keyset-pagination cursor payload (SPEC-TW-005
 * §14). Binds operation, evaluation time, the four keyset sort values,
 * filter fingerprint, scope fingerprint, actor, and sort version, so it
 * cannot be replayed with different filters, a changed authorization
 * scope, by a different actor, against a different endpoint, or after the
 * sort definition changes. {@code evaluationTime} is carried unchanged
 * from the first page to every subsequent page in the same session (§11),
 * so SLA urgency ranking stays stable while real time passes.
 */
public record SupportQueueCursor(
    int version,
    Instant evaluationTime,
    int lastSlaRank,
    int lastPriorityRank,
    Instant lastCreatedAt,
    UUID lastTicketId,
    String filterFingerprint,
    String scopeFingerprint,
    String principalSubject,
    int sortVersion,
    String operation,
    Instant issuedAt,
    Instant expiresAt
) {

    public static final int CURRENT_VERSION = 1;
    public static final String OPERATION = "support_queue";
}
