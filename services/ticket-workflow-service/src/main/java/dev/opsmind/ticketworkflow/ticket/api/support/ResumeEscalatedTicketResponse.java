package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResumeEscalatedTicketResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String resumeReasonCode,
    String resumedBy,
    Instant resumedAt,
    UUID resolutionCycleId,
    String ownershipStatus,
    long version
) {
}
