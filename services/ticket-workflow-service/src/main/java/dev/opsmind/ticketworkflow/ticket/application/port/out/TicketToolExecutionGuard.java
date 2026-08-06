package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-019: the ticket + authorizing-approval-request projection needed
 * to classify an inbound {@code tool.execution.completed.v1} event before
 * applying it. Phase 05 clears {@code ticket_approval_requests}' {@code
 * authorization_reference} back onto the ticket only transiently (it is
 * never cleared from the approval-request row itself once granted or
 * auto-approved), so that row remains the durable source of the
 * authorization a tool execution must match.
 */
public record TicketToolExecutionGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus ticketStatus,
    long ticketVersion,
    SupportQueueId supportQueueId,
    String assigneeId,
    UUID approvalRequestId,
    String actionType,
    String authorizationReference
) {
}
