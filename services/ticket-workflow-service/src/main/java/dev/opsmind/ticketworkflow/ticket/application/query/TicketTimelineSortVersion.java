package dev.opsmind.ticketworkflow.ticket.application.query;

import java.util.List;

/**
 * The frozen Timeline sort definition (SPEC-TW-006 §11, §12): {@code
 * occurredAt ASC, itemTypeRank ASC, itemId ASC}. A single source of truth
 * for the cursor's version binding (§15) and the response's {@code sort}
 * block (§19, §20).
 */
public final class TicketTimelineSortVersion {

    public static final int CURRENT_VERSION = 1;
    public static final List<String> FIELDS = List.of("occurredAt:asc", "itemTypeRank:asc", "itemId:asc");

    private TicketTimelineSortVersion() {
    }
}
