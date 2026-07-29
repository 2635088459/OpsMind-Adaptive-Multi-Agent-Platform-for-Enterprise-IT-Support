package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * {@code TRIAGED} (SPEC-TW-007) is the only value added by Phase 03's
 * lifecycle work so far. Phase 03's own vocabulary calls the pre-triage
 * state "OPEN" and the post-triage state "TRIAGED"; this codebase's
 * pre-existing initial state is {@link #NEW} (set by SPEC-TW-001's Create
 * Ticket), which SPEC-TW-007 treats as the "OPEN" precondition rather than
 * renaming it and rippling that change through every already-shipped
 * SPEC-TW-001..006 fixture and test. {@link #TRIAGING} and the other
 * legacy values below predate Phase 03 (a different, more granular
 * workflow-execution model) and are left untouched — Triage does not use
 * or remove them.
 */
public enum TicketStatus {
    NEW,
    TRIAGED,
    TRIAGING,
    INVESTIGATING,
    WAITING_FOR_USER,
    WAITING_FOR_APPROVAL,
    EXECUTING,
    VERIFYING,
    RESOLVED,
    CLOSED,
    ESCALATED,
    FAILED,
    CANCELLED
}
