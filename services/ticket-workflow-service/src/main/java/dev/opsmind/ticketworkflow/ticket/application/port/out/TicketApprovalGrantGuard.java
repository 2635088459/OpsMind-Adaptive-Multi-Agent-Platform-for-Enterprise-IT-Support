package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/** SPEC-TW-015: the ticket + open-approval-request projection needed to classify an inbound {@code approval.granted.v1} event before applying it. */
public record TicketApprovalGrantGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus ticketStatus,
    long ticketVersion,
    SupportQueueId supportQueueId,
    String assigneeId,
    UUID approvalRequestId,
    String requestStatus,
    String workflowId,
    String actionId,
    String actionType
) {
}
