package dev.opsmind.ticketworkflow.ticket.application.port.out;

public sealed interface TicketToolExecutionCompletedUpdateOutcome
    permits TicketToolExecutionCompletedUpdateOutcome.Applied, TicketToolExecutionCompletedUpdateOutcome.Conflict {

    record Applied(long newVersion) implements TicketToolExecutionCompletedUpdateOutcome {
    }

    /**
     * The guard-loaded version/status/authorization no longer matches at
     * write time (concurrent modification), or a concurrent delivery of the
     * same {@code toolExecutionId} already recorded a result first; the
     * caller reclassifies either case as {@code STALE}.
     */
    record Conflict() implements TicketToolExecutionCompletedUpdateOutcome {
    }
}
