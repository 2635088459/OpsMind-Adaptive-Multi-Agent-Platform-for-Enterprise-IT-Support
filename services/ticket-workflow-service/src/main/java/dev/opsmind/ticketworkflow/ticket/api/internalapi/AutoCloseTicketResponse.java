package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AutoCloseTicketResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String closeReasonCode,
    String closedBy,
    Instant closedAt,
    UUID resolutionCycleId,
    long version
) {
}
