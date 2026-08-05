package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoApprovalApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

public record ApplyAutoApprovedPolicyResult(
    ApplyAutoApprovedPolicyOutcome outcome,
    TicketId ticketId,
    UUID approvalRequestId,
    TicketStatus previousStatus,
    TicketStatus status,
    String authorizationReference,
    long version
) {

    public static ApplyAutoApprovedPolicyResult applied(TicketAutoApprovalApplied event) {
        return new ApplyAutoApprovedPolicyResult(
            ApplyAutoApprovedPolicyOutcome.APPLIED, event.ticketId(), event.approvalRequestId(),
            event.previousStatus(), event.newStatus(), event.authorizationReference(), event.aggregateVersion()
        );
    }

    public static ApplyAutoApprovedPolicyResult duplicate(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyAutoApprovedPolicyResult(ApplyAutoApprovedPolicyOutcome.DUPLICATE, ticketId, approvalRequestId, null, null, null, 0);
    }

    public static ApplyAutoApprovedPolicyResult stale(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyAutoApprovedPolicyResult(ApplyAutoApprovedPolicyOutcome.STALE, ticketId, approvalRequestId, null, null, null, 0);
    }

    public static ApplyAutoApprovedPolicyResult rejectedBusinessRule(TicketId ticketId, UUID approvalRequestId) {
        return new ApplyAutoApprovedPolicyResult(ApplyAutoApprovedPolicyOutcome.REJECTED_BUSINESS_RULE, ticketId, approvalRequestId, null, null, null, 0);
    }
}
