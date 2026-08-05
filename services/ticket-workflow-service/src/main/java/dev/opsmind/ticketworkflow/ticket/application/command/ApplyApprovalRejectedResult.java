package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalRejectedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

public record ApplyApprovalRejectedResult(
    ApplyApprovalRejectedOutcome outcome,
    TicketId ticketId,
    UUID approvalRequestId,
    TicketStatus previousStatus,
    TicketStatus status,
    long version
) {

    public static ApplyApprovalRejectedResult applied(TicketApprovalRejectedApplied event) {
        return new ApplyApprovalRejectedResult(
            ApplyApprovalRejectedOutcome.APPLIED, event.ticketId(), event.approvalRequestId(),
            event.previousStatus(), event.newStatus(), event.aggregateVersion()
        );
    }

    public static ApplyApprovalRejectedResult duplicate(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalRejectedResult(ApplyApprovalRejectedOutcome.DUPLICATE, ticketId, approvalRequestId, null, null, 0);
    }

    public static ApplyApprovalRejectedResult stale(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalRejectedResult(ApplyApprovalRejectedOutcome.STALE, ticketId, approvalRequestId, null, null, 0);
    }

    public static ApplyApprovalRejectedResult rejectedBusinessRule(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalRejectedResult(ApplyApprovalRejectedOutcome.REJECTED_BUSINESS_RULE, ticketId, approvalRequestId, null, null, 0);
    }
}
