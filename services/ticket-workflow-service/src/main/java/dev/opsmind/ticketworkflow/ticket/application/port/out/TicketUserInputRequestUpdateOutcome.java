package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public sealed interface TicketUserInputRequestUpdateOutcome
    permits TicketUserInputRequestUpdateOutcome.Created,
            TicketUserInputRequestUpdateOutcome.TicketMissing,
            TicketUserInputRequestUpdateOutcome.VersionMismatch,
            TicketUserInputRequestUpdateOutcome.InvalidState,
            TicketUserInputRequestUpdateOutcome.NotAssigned,
            TicketUserInputRequestUpdateOutcome.RequestAlreadyOpen {

    record Created(long newVersion) implements TicketUserInputRequestUpdateOutcome {
    }

    record TicketMissing() implements TicketUserInputRequestUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketUserInputRequestUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketUserInputRequestUpdateOutcome {
    }

    record NotAssigned() implements TicketUserInputRequestUpdateOutcome {
    }

    /** The partial unique index rejected the insert (defensive: the ticket-status guard should already prevent this). */
    record RequestAlreadyOpen() implements TicketUserInputRequestUpdateOutcome {
    }
}
