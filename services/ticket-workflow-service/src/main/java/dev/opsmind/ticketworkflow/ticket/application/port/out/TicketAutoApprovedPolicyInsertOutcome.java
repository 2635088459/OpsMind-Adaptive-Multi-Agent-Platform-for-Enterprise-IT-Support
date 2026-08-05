package dev.opsmind.ticketworkflow.ticket.application.port.out;

public sealed interface TicketAutoApprovedPolicyInsertOutcome
    permits TicketAutoApprovedPolicyInsertOutcome.Applied, TicketAutoApprovedPolicyInsertOutcome.TicketConflict,
    TicketAutoApprovedPolicyInsertOutcome.DuplicateConflict {

    record Applied(long newVersion) implements TicketAutoApprovedPolicyInsertOutcome {
    }

    /** The guard-loaded ticket version/status no longer matches at write time (concurrent modification); the caller reclassifies this as {@code STALE}. */
    record TicketConflict() implements TicketAutoApprovedPolicyInsertOutcome {
    }

    /** Concurrent delivery of the same {@code policyDecisionId} raced past the guard read and lost the {@code approval_id} uniqueness race; the caller reclassifies this as {@code DUPLICATE}. */
    record DuplicateConflict() implements TicketAutoApprovedPolicyInsertOutcome {
    }
}
