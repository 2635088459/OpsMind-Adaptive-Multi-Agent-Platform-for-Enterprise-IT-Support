package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record StartVerificationResponse(
    UUID ticketId,
    String verificationId,
    UUID resolutionCycleId,
    String workflowId,
    String toolResultId,
    int attemptNumber,
    String verificationType,
    String previousStatus,
    String status,
    String startedBy,
    Instant startedAt,
    long version
) {
}
