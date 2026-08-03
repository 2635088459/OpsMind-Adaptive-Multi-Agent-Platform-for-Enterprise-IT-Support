package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketTriageUpdateOutcome} (SPEC-TW-007 §8). */
public sealed interface TicketAssignmentUpdateOutcome
    permits TicketAssignmentUpdateOutcome.Updated,
            TicketAssignmentUpdateOutcome.TicketMissing,
            TicketAssignmentUpdateOutcome.VersionMismatch,
            TicketAssignmentUpdateOutcome.InvalidState {

    record Updated(long newVersion) implements TicketAssignmentUpdateOutcome {
    }

    record TicketMissing() implements TicketAssignmentUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketAssignmentUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketAssignmentUpdateOutcome {
    }
}
