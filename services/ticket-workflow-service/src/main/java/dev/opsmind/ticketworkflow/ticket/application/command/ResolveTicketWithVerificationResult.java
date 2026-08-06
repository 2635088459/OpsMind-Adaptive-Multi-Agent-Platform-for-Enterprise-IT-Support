package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record ResolveTicketWithVerificationResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    String verificationId,
    String verificationEvidenceId,
    ResolutionCode resolutionCode,
    String resolutionSummary,
    String resolvedBy,
    Instant resolvedAt,
    UUID resolutionCycleId,
    Instant autoCloseDueAt,
    long version,
    boolean replayed
) {
}
