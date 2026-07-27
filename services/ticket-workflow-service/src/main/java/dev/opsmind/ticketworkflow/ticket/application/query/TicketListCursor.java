package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded keyset-pagination cursor payload (SPEC-TW-003 §8). Bound to the
 * fixed sort, the requesting filters (via {@code filterFingerprint}), the
 * owning principal, and the operation, so it cannot be replayed with
 * different filters, by a different actor, or against a different
 * endpoint. Sort keys ({@code lastCreatedAt}, {@code lastTicketId}) are
 * immutable Ticket fields (SPEC-TW-003 §9).
 */
public record TicketListCursor(
    int version,
    Instant lastCreatedAt,
    UUID lastTicketId,
    String filterFingerprint,
    String sort,
    String principalSubject,
    String operation,
    Instant issuedAt,
    Instant expiresAt
) {

    public static final int CURRENT_VERSION = 1;
    public static final String SORT = "createdAt:desc,ticketId:desc";
    public static final String OPERATION = "list_requester_tickets";
}
