package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

public record TicketApprovalExpiredUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID approvalRequestId,
    String approvalId,
    Instant expiredAt,
    String expirationReason,
    String expiredEventId,
    Instant updatedAt
) {
}
