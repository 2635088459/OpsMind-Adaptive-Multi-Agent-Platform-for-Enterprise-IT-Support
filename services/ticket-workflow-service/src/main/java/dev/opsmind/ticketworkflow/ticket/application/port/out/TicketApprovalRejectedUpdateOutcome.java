package dev.opsmind.ticketworkflow.ticket.application.port.out;

public sealed interface TicketApprovalRejectedUpdateOutcome
    permits TicketApprovalRejectedUpdateOutcome.Applied, TicketApprovalRejectedUpdateOutcome.Conflict {

    record Applied(long newVersion) implements TicketApprovalRejectedUpdateOutcome {
    }

    /** The guard-loaded version/status/approval_reference no longer matches at write time (concurrent modification); the caller reclassifies this as {@code STALE}. */
    record Conflict() implements TicketApprovalRejectedUpdateOutcome {
    }
}
