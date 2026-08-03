package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReopenTicketResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    UUID previousResolutionCycleId,
    UUID newResolutionCycleId,
    String reopenReasonCode,
    String reopenedBy,
    Instant reopenedAt,
    int reopenCount,
    String ownershipStatus,
    long version
) {
}
