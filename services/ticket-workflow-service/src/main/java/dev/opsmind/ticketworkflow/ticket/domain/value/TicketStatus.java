package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * {@code TRIAGED} (SPEC-TW-007) and {@code ASSIGNED} (SPEC-TW-008) are the
 * only values added by Phase 03's lifecycle work so far. Phase 03's own
 * vocabulary calls the pre-triage state "OPEN" and the post-triage state
 * "TRIAGED"; this codebase's pre-existing initial state is {@link #NEW}
 * (set by SPEC-TW-001's Create Ticket), which SPEC-TW-007 treats as the
 * "OPEN" precondition rather than renaming it and rippling that change
 * through every already-shipped SPEC-TW-001..006 fixture and test.
 * {@code IN_PROGRESS} is introduced by SPEC-TW-009 as Phase 03's persisted
 * "work has started" state and is now included in the assignment
 * reassignable-status set.
 * {@link #TRIAGING} and the other legacy values below predate Phase 03 (a
 * different, more granular workflow-execution model) and are left
 * untouched.
 */
public enum TicketStatus {
    NEW,
    TRIAGED,
    ASSIGNED,
    IN_PROGRESS,
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
