package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

public record VerifiedResolutionUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID resolutionCycleId,
    String verificationId,
    String verificationEvidenceId,
    ResolutionCode resolutionCode,
    String resolutionSummary,
    String resolvedByType,
    String resolvedById,
    Instant resolvedAt,
    Instant autoCloseDueAt,
    Instant updatedAt
) {
}
