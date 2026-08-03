package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalGrantedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

public record ApplyApprovalGrantedResult(
    ApplyApprovalGrantedOutcome outcome,
    TicketId ticketId,
    UUID approvalRequestId,
    TicketStatus previousStatus,
    TicketStatus status,
    String authorizationReference,
    long version
) {

    public static ApplyApprovalGrantedResult applied(TicketApprovalGrantedApplied event) {
        return new ApplyApprovalGrantedResult(
            ApplyApprovalGrantedOutcome.APPLIED, event.ticketId(), event.approvalRequestId(),
            event.previousStatus(), event.newStatus(), event.authorizationReference(), event.aggregateVersion()
        );
    }

    public static ApplyApprovalGrantedResult duplicate(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalGrantedResult(ApplyApprovalGrantedOutcome.DUPLICATE, ticketId, approvalRequestId, null, null, null, 0);
    }

    public static ApplyApprovalGrantedResult stale(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalGrantedResult(ApplyApprovalGrantedOutcome.STALE, ticketId, approvalRequestId, null, null, null, 0);
    }

    public static ApplyApprovalGrantedResult rejectedBusinessRule(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyApprovalGrantedResult(ApplyApprovalGrantedOutcome.REJECTED_BUSINESS_RULE, ticketId, approvalRequestId, null, null, null, 0);
    }
}
