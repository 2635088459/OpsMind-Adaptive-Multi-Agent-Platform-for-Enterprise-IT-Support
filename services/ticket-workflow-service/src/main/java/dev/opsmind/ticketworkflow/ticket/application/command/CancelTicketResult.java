package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record CancelTicketResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    CancelReasonCode cancelReasonCode,
    String cancelledBy,
    Instant cancelledAt,
    UUID resolutionCycleId,
    long version,
    boolean replayed
) {
}
