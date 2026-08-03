package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record ReopenTicketResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    UUID previousResolutionCycleId,
    UUID newResolutionCycleId,
    ReopenReasonCode reopenReasonCode,
    String reopenedBy,
    Instant reopenedAt,
    int reopenCount,
    OwnershipStatus ownershipStatus,
    long version,
    boolean replayed
) {
}
