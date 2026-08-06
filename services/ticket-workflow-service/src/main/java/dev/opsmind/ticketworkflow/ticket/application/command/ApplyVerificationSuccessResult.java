package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationSuccessApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public record ApplyVerificationSuccessResult(
    ApplyVerificationSuccessOutcome outcome,
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    String verificationId,
    String verificationEvidenceId,
    long version
) {

    public static ApplyVerificationSuccessResult applied(TicketVerificationSuccessApplied event) {
        return new ApplyVerificationSuccessResult(
            ApplyVerificationSuccessOutcome.APPLIED, event.ticketId(), event.previousStatus(), event.newStatus(),
            event.verificationId(), event.verificationEvidenceId(), event.aggregateVersion()
        );
    }

    public static ApplyVerificationSuccessResult duplicate(TicketId ticketId, String verificationId) {
        return new ApplyVerificationSuccessResult(ApplyVerificationSuccessOutcome.DUPLICATE, ticketId, null, null, verificationId, null, 0);
    }

    public static ApplyVerificationSuccessResult stale(TicketId ticketId, String verificationId) {
        return new ApplyVerificationSuccessResult(ApplyVerificationSuccessOutcome.STALE, ticketId, null, null, verificationId, null, 0);
    }

    public static ApplyVerificationSuccessResult conflictRequiresReconciliation(TicketId ticketId, String verificationId) {
        return new ApplyVerificationSuccessResult(ApplyVerificationSuccessOutcome.CONFLICT_REQUIRES_RECONCILIATION, ticketId, null, null, verificationId, null, 0);
    }
}
