package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RequestUserInputResponse(
    UUID ticketId,
    UUID requestId,
    String previousStatus,
    String status,
    String prompt,
    String requestedBy,
    Instant requestedAt,
    Instant waitingForRequesterSince,
    long version
) {
}
