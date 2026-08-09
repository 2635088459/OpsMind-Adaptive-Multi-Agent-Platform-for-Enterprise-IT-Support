package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfirmResolutionResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String reasonCode,
    String confirmedBy,
    Instant confirmedAt,
    UUID resolutionCycleId,
    long version
) {
}
