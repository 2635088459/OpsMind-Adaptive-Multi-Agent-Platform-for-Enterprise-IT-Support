package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-017: the ticket + open-approval-request projection needed to
 * classify an inbound {@code approval.expired.v1} event before applying it.
 * {@code expiresAt} is the request's own stored expiry (SPEC-TW-014's
 * {@code ticket.ticket_approval_requests.expires_at}, nullable since no
 * shipped writer currently populates it) — when present, it is the
 * reference point for SPEC-TW-017's {@code expiredAt >= expiresAt}
 * business-rule check.
 */
public record TicketApprovalExpirationGuard(
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
    Instant expiresAt
) {
}
