package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public sealed interface TicketCloseUpdateOutcome
    permits TicketCloseUpdateOutcome.Updated,
            TicketCloseUpdateOutcome.TicketMissing,
            TicketCloseUpdateOutcome.VersionMismatch,
            TicketCloseUpdateOutcome.InvalidState,
            TicketCloseUpdateOutcome.ResolutionCycleConflict {

    record Updated(long newVersion) implements TicketCloseUpdateOutcome {
    }

    record TicketMissing() implements TicketCloseUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketCloseUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketCloseUpdateOutcome {
    }

    /** The cycle row was no longer {@code RESOLVED} at commit time (defensive, mirrors SPEC-TW-010's outcome). */
    record ResolutionCycleConflict() implements TicketCloseUpdateOutcome {
    }
}
