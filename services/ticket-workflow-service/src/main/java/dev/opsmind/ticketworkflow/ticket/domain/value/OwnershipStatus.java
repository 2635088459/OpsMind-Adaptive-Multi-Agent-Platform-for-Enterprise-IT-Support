package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * SPEC-TW-011 domain-rules §4/AC-07: reopen never silently reassigns.
 * {@code ASSIGNEE_INACTIVE} and {@code UNASSIGNED} are informational —
 * neither blocks the reopen itself, both signal that active-work commands
 * must first correct ownership through assign/reassign.
 */
public enum OwnershipStatus {
    ACTIVE,
    ASSIGNEE_INACTIVE,
    UNASSIGNED
}
