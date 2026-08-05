package dev.opsmind.ticketworkflow.ticket.application.port.out;

public sealed interface TicketApprovalExpiredUpdateOutcome
    permits TicketApprovalExpiredUpdateOutcome.Applied, TicketApprovalExpiredUpdateOutcome.Conflict {

    record Applied(long newVersion) implements TicketApprovalExpiredUpdateOutcome {
    }

    /** The guard-loaded version/status/approval_reference no longer matches at write time (concurrent modification); the caller reclassifies this as {@code STALE}. */
    record Conflict() implements TicketApprovalExpiredUpdateOutcome {
    }
}
