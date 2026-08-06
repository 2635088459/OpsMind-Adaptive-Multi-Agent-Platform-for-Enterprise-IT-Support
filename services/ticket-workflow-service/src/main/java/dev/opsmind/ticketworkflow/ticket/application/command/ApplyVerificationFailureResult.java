package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationFailureApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public record ApplyVerificationFailureResult(
    ApplyVerificationFailureOutcome outcome,
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    String verificationId,
    String failureClass,
    long version
) {

    public static ApplyVerificationFailureResult applied(TicketVerificationFailureApplied event) {
        ApplyVerificationFailureOutcome outcome = switch (event.newStatus()) {
            case IN_PROGRESS -> ApplyVerificationFailureOutcome.APPLIED_RETRYABLE;
            case ESCALATED -> ApplyVerificationFailureOutcome.APPLIED_ESCALATED;
            case FAILED -> ApplyVerificationFailureOutcome.APPLIED_PIPELINE_FAILED;
            default -> throw new IllegalStateException("unexpected verification failure target status: " + event.newStatus());
        };
        return new ApplyVerificationFailureResult(
            outcome, event.ticketId(), event.previousStatus(), event.newStatus(), event.verificationId(), event.failureClass(), event.aggregateVersion()
        );
    }

    public static ApplyVerificationFailureResult duplicate(TicketId ticketId, String verificationId) {
        return new ApplyVerificationFailureResult(ApplyVerificationFailureOutcome.DUPLICATE, ticketId, null, null, verificationId, null, 0);
    }

    public static ApplyVerificationFailureResult stale(TicketId ticketId, String verificationId) {
        return new ApplyVerificationFailureResult(ApplyVerificationFailureOutcome.STALE, ticketId, null, null, verificationId, null, 0);
    }

    public static ApplyVerificationFailureResult conflictRequiresReconciliation(TicketId ticketId, String verificationId) {
        return new ApplyVerificationFailureResult(ApplyVerificationFailureOutcome.CONFLICT_REQUIRES_RECONCILIATION, ticketId, null, null, verificationId, null, 0);
    }
}
