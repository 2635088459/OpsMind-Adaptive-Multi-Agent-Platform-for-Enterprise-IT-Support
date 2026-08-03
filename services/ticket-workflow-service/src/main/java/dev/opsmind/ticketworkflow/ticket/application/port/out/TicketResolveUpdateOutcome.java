package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketStatusTransitionUpdateOutcome} (SPEC-TW-009), plus a resolution-cycle race outcome. */
public sealed interface TicketResolveUpdateOutcome
    permits TicketResolveUpdateOutcome.Updated,
            TicketResolveUpdateOutcome.TicketMissing,
            TicketResolveUpdateOutcome.VersionMismatch,
            TicketResolveUpdateOutcome.InvalidState,
            TicketResolveUpdateOutcome.NotAssigned,
            TicketResolveUpdateOutcome.ResolutionCycleConflict {

    record Updated(long newVersion) implements TicketResolveUpdateOutcome {
    }

    record TicketMissing() implements TicketResolveUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketResolveUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketResolveUpdateOutcome {
    }

    record NotAssigned() implements TicketResolveUpdateOutcome {
    }

    /**
     * The ticket row's own optimistic-lock UPDATE succeeded but the
     * resolution-cycle row was no longer {@code ACTIVE} at commit time
     * (defensive: the guard already checked this, but a second writer
     * cannot win the ticket UPDATE without also owning this exact cycle).
     * Rolled back by the caller's {@code @Transactional} boundary.
     */
    record ResolutionCycleConflict() implements TicketResolveUpdateOutcome {
    }
}
