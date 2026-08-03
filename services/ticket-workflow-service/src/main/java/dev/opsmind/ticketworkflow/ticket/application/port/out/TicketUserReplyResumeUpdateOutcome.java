package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public sealed interface TicketUserReplyResumeUpdateOutcome
    permits TicketUserReplyResumeUpdateOutcome.Updated,
            TicketUserReplyResumeUpdateOutcome.TicketMissing,
            TicketUserReplyResumeUpdateOutcome.VersionMismatch,
            TicketUserReplyResumeUpdateOutcome.InvalidState,
            TicketUserReplyResumeUpdateOutcome.RequestNotOpen {

    record Updated(long newVersion) implements TicketUserReplyResumeUpdateOutcome {
    }

    record TicketMissing() implements TicketUserReplyResumeUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketUserReplyResumeUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketUserReplyResumeUpdateOutcome {
    }

    /** The request row was no longer {@code OPEN} at commit time (defensive: the guard already checked this). */
    record RequestNotOpen() implements TicketUserReplyResumeUpdateOutcome {
    }
}
