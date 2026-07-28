package dev.opsmind.ticketworkflow.ticket.application.query;

import java.util.List;

/**
 * The frozen Support Queue sort definition (SPEC-TW-005 §13, §16): {@code
 * slaRank ASC, priorityRank ASC, createdAt ASC, ticketId ASC}. A single
 * source of truth for the cursor's version binding (§14) and the
 * response's {@code sort} block (§16), so the SQL rank expressions,
 * cursor values, and API contract cannot silently drift apart (§15).
 */
public final class SupportQueueSortVersion {

    public static final int CURRENT_VERSION = 1;
    public static final List<String> FIELDS = List.of("slaRank:asc", "priorityRank:asc", "createdAt:asc", "ticketId:asc");

    private SupportQueueSortVersion() {
    }
}
