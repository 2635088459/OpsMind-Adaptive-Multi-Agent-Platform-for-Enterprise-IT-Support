package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/**
 * The authoritative result of the version/state-guarded {@code UPDATE}
 * (SPEC-TW-007 §8). The zero-affected-rows case is reclassified into
 * exactly one of three reasons without another statement leaking whether
 * the mismatch was caused by concurrent state versus version drift —
 * mirrors {@link dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome}'s
 * sealed-outcome shape.
 */
public sealed interface TicketTriageUpdateOutcome
    permits TicketTriageUpdateOutcome.Updated,
            TicketTriageUpdateOutcome.TicketMissing,
            TicketTriageUpdateOutcome.VersionMismatch,
            TicketTriageUpdateOutcome.InvalidState {

    record Updated(long newVersion) implements TicketTriageUpdateOutcome {
    }

    record TicketMissing() implements TicketTriageUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketTriageUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketTriageUpdateOutcome {
    }
}
