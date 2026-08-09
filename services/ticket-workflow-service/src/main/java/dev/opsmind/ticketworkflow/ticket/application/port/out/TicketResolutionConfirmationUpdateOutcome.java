package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketCloseUpdateOutcome} (SPEC-TW-011) exactly. */
public sealed interface TicketResolutionConfirmationUpdateOutcome
    permits TicketResolutionConfirmationUpdateOutcome.Updated,
            TicketResolutionConfirmationUpdateOutcome.TicketMissing,
            TicketResolutionConfirmationUpdateOutcome.VersionMismatch,
            TicketResolutionConfirmationUpdateOutcome.InvalidState,
            TicketResolutionConfirmationUpdateOutcome.ResolutionCycleConflict {

    record Updated(long newVersion) implements TicketResolutionConfirmationUpdateOutcome {
    }

    record TicketMissing() implements TicketResolutionConfirmationUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketResolutionConfirmationUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketResolutionConfirmationUpdateOutcome {
    }

    /** The cycle row was no longer {@code RESOLVED} at commit time (defensive, mirrors SPEC-TW-011's outcome). */
    record ResolutionCycleConflict() implements TicketResolutionConfirmationUpdateOutcome {
    }
}
