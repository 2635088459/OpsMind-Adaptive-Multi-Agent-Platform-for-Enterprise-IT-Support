package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalExpiredApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

public record ApplyApprovalExpiredResult(
    ApplyApprovalExpiredOutcome outcome,
    TicketId ticketId,
    UUID approvalRequestId,
    TicketStatus previousStatus,
    TicketStatus status,
    long version
) {

    public static ApplyApprovalExpiredResult applied(TicketApprovalExpiredApplied event) {
        return new ApplyApprovalExpiredResult(
            ApplyApprovalExpiredOutcome.APPLIED, event.ticketId(), event.approvalRequestId(),
            event.previousStatus(), event.newStatus(), event.aggregateVersion()
        );
    }

    public static ApplyApprovalExpiredResult duplicate(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalExpiredResult(ApplyApprovalExpiredOutcome.DUPLICATE, ticketId, approvalRequestId, null, null, 0);
    }

    public static ApplyApprovalExpiredResult stale(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalExpiredResult(ApplyApprovalExpiredOutcome.STALE, ticketId, approvalRequestId, null, null, 0);
    }

    public static ApplyApprovalExpiredResult rejectedBusinessRule(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalExpiredResult(ApplyApprovalExpiredOutcome.REJECTED_BUSINESS_RULE, ticketId, approvalRequestId, null, null, 0);
    }
}
