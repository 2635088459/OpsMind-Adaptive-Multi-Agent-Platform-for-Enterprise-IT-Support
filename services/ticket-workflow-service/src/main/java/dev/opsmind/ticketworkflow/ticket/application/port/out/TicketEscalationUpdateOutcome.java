package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketCancelUpdateOutcome} (SPEC-TW-029)'s shape — Escalate, like Cancel, accepts more than one source status. */
public sealed interface TicketEscalationUpdateOutcome
    permits TicketEscalationUpdateOutcome.Updated,
            TicketEscalationUpdateOutcome.TicketMissing,
            TicketEscalationUpdateOutcome.VersionMismatch,
            TicketEscalationUpdateOutcome.InvalidState {

    record Updated(long newVersion) implements TicketEscalationUpdateOutcome {
    }

    record TicketMissing() implements TicketEscalationUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketEscalationUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketEscalationUpdateOutcome {
    }
}
