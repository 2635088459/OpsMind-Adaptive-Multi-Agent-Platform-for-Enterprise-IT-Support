package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public sealed interface TicketApprovalRequestUpdateOutcome
    permits TicketApprovalRequestUpdateOutcome.Created,
            TicketApprovalRequestUpdateOutcome.TicketMissing,
            TicketApprovalRequestUpdateOutcome.VersionMismatch,
            TicketApprovalRequestUpdateOutcome.InvalidState,
            TicketApprovalRequestUpdateOutcome.NotAssigned,
            TicketApprovalRequestUpdateOutcome.RequestAlreadyOpen {

    record Created(long newVersion) implements TicketApprovalRequestUpdateOutcome {
    }

    record TicketMissing() implements TicketApprovalRequestUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketApprovalRequestUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketApprovalRequestUpdateOutcome {
    }

    record NotAssigned() implements TicketApprovalRequestUpdateOutcome {
    }

    /** The partial unique index rejected the insert (defensive: the ticket-status guard should already prevent this). */
    record RequestAlreadyOpen() implements TicketApprovalRequestUpdateOutcome {
    }
}
