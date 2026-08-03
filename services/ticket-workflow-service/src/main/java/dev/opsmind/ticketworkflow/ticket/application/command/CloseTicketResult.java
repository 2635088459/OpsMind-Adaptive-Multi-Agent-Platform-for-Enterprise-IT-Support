package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record CloseTicketResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    CloseReasonCode closeReasonCode,
    String closedBy,
    Instant closedAt,
    UUID resolutionCycleId,
    long version,
    boolean replayed
) {
}
