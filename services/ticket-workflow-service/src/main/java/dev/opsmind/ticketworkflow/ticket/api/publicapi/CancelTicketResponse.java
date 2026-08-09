package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CancelTicketResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String cancelReasonCode,
    String cancelledBy,
    Instant cancelledAt,
    UUID resolutionCycleId,
    long version
) {
}
