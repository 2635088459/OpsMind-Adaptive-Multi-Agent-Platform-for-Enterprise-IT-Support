package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

public record TicketCloseUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID resolutionCycleId,
    CloseReasonCode closeReasonCode,
    String closeReason,
    String closedByType,
    String closedById,
    Instant closedAt,
    Instant updatedAt
) {
}
