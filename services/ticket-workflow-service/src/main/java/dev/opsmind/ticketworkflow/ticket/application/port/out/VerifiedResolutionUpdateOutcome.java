package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketResolveUpdateOutcome} (SPEC-TW-010) exactly, plus a resolution-cycle race outcome. */
public sealed interface VerifiedResolutionUpdateOutcome
    permits VerifiedResolutionUpdateOutcome.Updated,
            VerifiedResolutionUpdateOutcome.TicketMissing,
            VerifiedResolutionUpdateOutcome.VersionMismatch,
            VerifiedResolutionUpdateOutcome.InvalidState,
            VerifiedResolutionUpdateOutcome.NotAssigned,
            VerifiedResolutionUpdateOutcome.ResolutionCycleConflict {

    record Updated(long newVersion) implements VerifiedResolutionUpdateOutcome {
    }

    record TicketMissing() implements VerifiedResolutionUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements VerifiedResolutionUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements VerifiedResolutionUpdateOutcome {
    }

    record NotAssigned() implements VerifiedResolutionUpdateOutcome {
    }

    /**
     * The ticket row's own optimistic-lock UPDATE succeeded but the
     * resolution-cycle row was no longer {@code ACTIVE} at commit time
     * (defensive: the guard already checked this, but a second writer
     * cannot win the ticket UPDATE without also owning this exact cycle).
     * Rolled back by the caller's {@code @Transactional} boundary.
     */
    record ResolutionCycleConflict() implements VerifiedResolutionUpdateOutcome {
    }
}
