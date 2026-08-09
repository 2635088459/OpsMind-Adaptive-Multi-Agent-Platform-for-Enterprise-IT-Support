package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RequesterReopenTicketResponse(
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
