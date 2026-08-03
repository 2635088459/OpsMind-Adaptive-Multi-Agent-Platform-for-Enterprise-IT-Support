package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public sealed interface TicketStatusTransitionUpdateOutcome
    permits TicketStatusTransitionUpdateOutcome.Updated,
            TicketStatusTransitionUpdateOutcome.TicketMissing,
            TicketStatusTransitionUpdateOutcome.VersionMismatch,
            TicketStatusTransitionUpdateOutcome.InvalidState,
            TicketStatusTransitionUpdateOutcome.NotAssigned {

    record Updated(long newVersion) implements TicketStatusTransitionUpdateOutcome {
    }

    record TicketMissing() implements TicketStatusTransitionUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketStatusTransitionUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketStatusTransitionUpdateOutcome {
    }

    record NotAssigned() implements TicketStatusTransitionUpdateOutcome {
    }
}
