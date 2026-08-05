package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/** SPEC-TW-016: the ticket + open-approval-request projection needed to classify an inbound {@code approval.rejected.v1} event before applying it. */
public record TicketApprovalRejectionGuard(
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
    String actionType,
    Instant requestedAt
) {
}
