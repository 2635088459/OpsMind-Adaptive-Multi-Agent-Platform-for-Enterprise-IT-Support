package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransitionTicketStatusResponse(
    UUID ticketId,
    String previousStatus,
    String status,
    String reason,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    Instant waitingForRequesterSince,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    String approvalReference,
    Instant transitionedAt,
    long version
) {
}
