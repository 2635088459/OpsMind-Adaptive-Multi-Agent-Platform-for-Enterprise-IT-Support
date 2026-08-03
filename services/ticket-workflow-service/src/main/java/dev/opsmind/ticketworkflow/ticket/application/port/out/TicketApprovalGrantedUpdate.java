package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

public record TicketApprovalGrantedUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID approvalRequestId,
    String approvalId,
    String approvedBy,
    Instant approvedAt,
    String authorizationReference,
    String grantedEventId,
    Instant updatedAt
) {
}
