package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserReplyResponse(
    UUID ticketId,
    UUID requestId,
    UUID messageId,
    String previousStatus,
    String status,
    Instant answeredAt,
    boolean resumeApplied,
    long version
) {
}
