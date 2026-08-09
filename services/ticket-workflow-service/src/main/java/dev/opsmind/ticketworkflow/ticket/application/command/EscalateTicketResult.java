package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record EscalateTicketResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    EscalationReasonCode escalationReasonCode,
    String escalatedBy,
    Instant escalatedAt,
    UUID resolutionCycleId,
    long version,
    boolean replayed
) {
}
