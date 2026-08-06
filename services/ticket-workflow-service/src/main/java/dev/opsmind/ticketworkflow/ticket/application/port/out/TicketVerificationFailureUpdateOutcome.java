package dev.opsmind.ticketworkflow.ticket.application.port.out;

public sealed interface TicketVerificationFailureUpdateOutcome
    permits TicketVerificationFailureUpdateOutcome.Applied, TicketVerificationFailureUpdateOutcome.Conflict {

    record Applied(long newVersion) implements TicketVerificationFailureUpdateOutcome {
    }

    /** The guard-loaded version/status/attempt state no longer matches at write time (concurrent modification); the caller reclassifies this as {@code STALE}. */
    record Conflict() implements TicketVerificationFailureUpdateOutcome {
    }
}
