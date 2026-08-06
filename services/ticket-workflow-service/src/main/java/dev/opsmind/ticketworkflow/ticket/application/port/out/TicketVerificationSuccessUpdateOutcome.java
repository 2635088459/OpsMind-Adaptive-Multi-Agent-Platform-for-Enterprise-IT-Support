package dev.opsmind.ticketworkflow.ticket.application.port.out;

public sealed interface TicketVerificationSuccessUpdateOutcome
    permits TicketVerificationSuccessUpdateOutcome.Applied, TicketVerificationSuccessUpdateOutcome.Conflict {

    record Applied(long newVersion) implements TicketVerificationSuccessUpdateOutcome {
    }

    /** The guard-loaded version/status/attempt state no longer matches at write time (concurrent modification); the caller reclassifies this as {@code STALE}. */
    record Conflict() implements TicketVerificationSuccessUpdateOutcome {
    }
}
