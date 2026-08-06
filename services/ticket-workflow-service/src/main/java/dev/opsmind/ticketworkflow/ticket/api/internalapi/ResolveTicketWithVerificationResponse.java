package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResolveTicketWithVerificationResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String verificationId,
    String verificationEvidenceId,
    String resolutionCode,
    String resolutionSummary,
    String resolvedBy,
    Instant resolvedAt,
    UUID resolutionCycleId,
    Instant autoCloseDueAt,
    long version
) {
}
