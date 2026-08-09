package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record EscalateTicketResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String escalationReasonCode,
    String escalatedBy,
    Instant escalatedAt,
    UUID resolutionCycleId,
    long version
) {
}
