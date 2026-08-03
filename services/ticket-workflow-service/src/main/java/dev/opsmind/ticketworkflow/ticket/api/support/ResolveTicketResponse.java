package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResolveTicketResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String resolutionCode,
    String resolutionSummary,
    String resolvedBy,
    Instant resolvedAt,
    UUID resolutionCycleId,
    Instant autoCloseDueAt,
    long version
) {
}
