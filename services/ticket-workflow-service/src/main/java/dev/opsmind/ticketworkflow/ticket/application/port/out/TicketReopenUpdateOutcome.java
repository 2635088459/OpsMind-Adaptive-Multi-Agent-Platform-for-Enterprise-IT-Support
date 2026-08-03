package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public sealed interface TicketReopenUpdateOutcome
    permits TicketReopenUpdateOutcome.Updated,
            TicketReopenUpdateOutcome.TicketMissing,
            TicketReopenUpdateOutcome.VersionMismatch,
            TicketReopenUpdateOutcome.InvalidState,
            TicketReopenUpdateOutcome.ResolutionCycleConflict {

    record Updated(long newVersion) implements TicketReopenUpdateOutcome {
    }

    record TicketMissing() implements TicketReopenUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketReopenUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketReopenUpdateOutcome {
    }

    /** The old cycle row was no longer in the expected terminal status at commit time (defensive). */
    record ResolutionCycleConflict() implements TicketReopenUpdateOutcome {
    }
}
