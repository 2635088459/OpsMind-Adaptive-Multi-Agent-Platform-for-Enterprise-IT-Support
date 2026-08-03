package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record TicketReopenUpdate(
    TicketId ticketId,
    long expectedVersion,
    TicketStatus expectedStatus,
    UUID previousResolutionCycleId,
    int previousResolutionCycleNumber,
    UUID newResolutionCycleId,
    int newReopenCount,
    ReopenReasonCode reopenReasonCode,
    String reopenReason,
    String reopenedByType,
    String reopenedById,
    Instant reopenedAt,
    Instant updatedAt
) {
}
