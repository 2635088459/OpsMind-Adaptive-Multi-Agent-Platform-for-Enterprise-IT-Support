package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

public record TicketEscalationResumeUpdate(
    TicketId ticketId,
    long expectedVersion,
    EscalationResumeReasonCode resumeReasonCode,
    String resumedByType,
    String resumedById,
    Instant resumedAt,
    Instant updatedAt
) {
}
