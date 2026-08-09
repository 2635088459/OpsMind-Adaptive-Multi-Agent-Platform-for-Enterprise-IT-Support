package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketCloseUpdateOutcome} (SPEC-TW-011) exactly. */
public sealed interface TicketAutoCloseUpdateOutcome
    permits TicketAutoCloseUpdateOutcome.Updated,
            TicketAutoCloseUpdateOutcome.TicketMissing,
            TicketAutoCloseUpdateOutcome.VersionMismatch,
            TicketAutoCloseUpdateOutcome.InvalidState,
            TicketAutoCloseUpdateOutcome.ResolutionCycleConflict {

    record Updated(long newVersion) implements TicketAutoCloseUpdateOutcome {
    }

    record TicketMissing() implements TicketAutoCloseUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketAutoCloseUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketAutoCloseUpdateOutcome {
    }

    /** The cycle row was no longer {@code RESOLVED} at commit time (defensive, mirrors SPEC-TW-011's outcome). */
    record ResolutionCycleConflict() implements TicketAutoCloseUpdateOutcome {
    }
}
