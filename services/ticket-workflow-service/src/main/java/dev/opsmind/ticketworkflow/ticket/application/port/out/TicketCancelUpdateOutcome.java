package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketReopenUpdateOutcome} (SPEC-TW-011)'s shape — Cancel, like Reopen, accepts more than one source status. */
public sealed interface TicketCancelUpdateOutcome
    permits TicketCancelUpdateOutcome.Updated,
            TicketCancelUpdateOutcome.TicketMissing,
            TicketCancelUpdateOutcome.VersionMismatch,
            TicketCancelUpdateOutcome.InvalidState,
            TicketCancelUpdateOutcome.ResolutionCycleConflict {

    record Updated(long newVersion) implements TicketCancelUpdateOutcome {
    }

    record TicketMissing() implements TicketCancelUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketCancelUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketCancelUpdateOutcome {
    }

    /** The resolution-cycle row was not found for this ticket at commit time (defensive — {@code current_resolution_cycle_id} is otherwise always populated). */
    record ResolutionCycleConflict() implements TicketCancelUpdateOutcome {
    }
}
