package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

public record TicketEscalationUpdate(
    TicketId ticketId,
    long expectedVersion,
    TicketStatus expectedStatus,
    EscalationReasonCode escalationReasonCode,
    String escalatedByType,
    String escalatedById,
    Instant escalatedAt,
    Instant updatedAt
) {
}
