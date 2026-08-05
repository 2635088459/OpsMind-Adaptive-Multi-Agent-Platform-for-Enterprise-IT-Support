package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-018: the ticket projection needed to classify an inbound {@code
 * policy.action-auto-approved.v1} event before applying it. Unlike SPEC-TW-015/
 * 016/017's guards, there is no prior open approval-request row to join
 * against — auto-approval never went through SPEC-TW-014's request-approval
 * flow. {@code existingApprovalRequestId} is populated only when a row
 * already exists for this event's {@code policyDecisionId} (the duplicate
 * check), sourced from the same globally-unique {@code approval_id} column
 * the other three specs use.
 */
public record TicketAutoApprovedPolicyGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus ticketStatus,
    long ticketVersion,
    SupportQueueId supportQueueId,
    String assigneeId,
    UUID existingApprovalRequestId
) {
}
