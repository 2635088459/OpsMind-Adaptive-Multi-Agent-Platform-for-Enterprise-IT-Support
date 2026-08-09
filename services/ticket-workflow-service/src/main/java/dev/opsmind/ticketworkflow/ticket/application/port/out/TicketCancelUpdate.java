package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record TicketCancelUpdate(
    TicketId ticketId,
    long expectedVersion,
    TicketStatus expectedStatus,
    UUID resolutionCycleId,
    CancelReasonCode cancelReasonCode,
    String cancelReason,
    String cancelledByType,
    String cancelledById,
    Instant cancelledAt,
    Instant updatedAt
) {
}
