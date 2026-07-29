package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;

/**
 * Decoded Timeline keyset-pagination cursor payload (SPEC-TW-006 §15).
 * Binds operation, Ticket, actor, authorization-scope fingerprint, view
 * type, visibility-policy version, the fixed snapshot boundary, sort
 * version, and the three-column keyset boundary — so it cannot be
 * replayed against a different Ticket, by a different actor, under a
 * changed authorization scope, under a different resolved view, after a
 * visibility-policy or sort-definition change, or against a different
 * endpoint. {@code snapshotAt} is carried unchanged from the first page
 * to every later page in the same session (§13), so items created after
 * the first page never enter a later page of that same cursor.
 */
public record TicketTimelineCursor(
    int version,
    String ticketId,
    String principalSubject,
    String scopeFingerprint,
    String viewType,
    int visibilityPolicyVersion,
    Instant snapshotAt,
    Instant lastOccurredAt,
    int lastItemTypeRank,
    String lastItemId,
    int sortVersion,
    String operation,
    Instant issuedAt,
    Instant expiresAt
) {

    public static final int CURRENT_VERSION = 1;
    public static final int CURRENT_VISIBILITY_POLICY_VERSION = 1;
    public static final String OPERATION = "ticket_timeline";
}
