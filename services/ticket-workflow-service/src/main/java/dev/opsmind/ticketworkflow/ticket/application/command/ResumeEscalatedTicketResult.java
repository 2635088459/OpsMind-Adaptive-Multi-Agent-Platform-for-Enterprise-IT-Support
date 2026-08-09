package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record ResumeEscalatedTicketResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    EscalationResumeReasonCode resumeReasonCode,
    String resumedBy,
    Instant resumedAt,
    UUID resolutionCycleId,
    OwnershipStatus ownershipStatus,
    long version,
    boolean replayed
) {
}
