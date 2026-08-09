package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

public record TicketResolutionConfirmationUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID resolutionCycleId,
    ResolutionConfirmationReasonCode reasonCode,
    String reason,
    String confirmedByType,
    String confirmedById,
    Instant confirmedAt,
    Instant updatedAt
) {
}
