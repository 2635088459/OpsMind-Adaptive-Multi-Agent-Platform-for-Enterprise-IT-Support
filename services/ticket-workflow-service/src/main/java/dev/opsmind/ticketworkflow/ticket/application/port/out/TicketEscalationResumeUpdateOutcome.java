package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public sealed interface TicketEscalationResumeUpdateOutcome
    permits TicketEscalationResumeUpdateOutcome.Updated,
            TicketEscalationResumeUpdateOutcome.TicketMissing,
            TicketEscalationResumeUpdateOutcome.VersionMismatch,
            TicketEscalationResumeUpdateOutcome.InvalidState {

    record Updated(long newVersion) implements TicketEscalationResumeUpdateOutcome {
    }

    record TicketMissing() implements TicketEscalationResumeUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketEscalationResumeUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketEscalationResumeUpdateOutcome {
    }
}
