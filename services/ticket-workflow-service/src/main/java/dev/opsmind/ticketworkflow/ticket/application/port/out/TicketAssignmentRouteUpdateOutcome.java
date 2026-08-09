package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@link TicketAssignmentUpdateOutcome} (SPEC-TW-008). */
public sealed interface TicketAssignmentRouteUpdateOutcome
    permits TicketAssignmentRouteUpdateOutcome.Updated,
            TicketAssignmentRouteUpdateOutcome.TicketMissing,
            TicketAssignmentRouteUpdateOutcome.VersionMismatch,
            TicketAssignmentRouteUpdateOutcome.InvalidState {

    record Updated(long newVersion) implements TicketAssignmentRouteUpdateOutcome {
    }

    record TicketMissing() implements TicketAssignmentRouteUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketAssignmentRouteUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketAssignmentRouteUpdateOutcome {
    }
}
