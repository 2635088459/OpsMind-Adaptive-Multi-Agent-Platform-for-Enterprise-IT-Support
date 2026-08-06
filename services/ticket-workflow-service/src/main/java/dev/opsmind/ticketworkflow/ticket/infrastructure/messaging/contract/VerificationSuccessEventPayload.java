package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-023 event-contract / 06-event-contracts CON-014 {@code verification.completed} (result = SUCCESS) payload shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationSuccessEventPayload(
    String workflowId,
    UUID resolutionCycleId,
    String verificationId,
    int attemptNumber,
    String result,
    String verificationEvidenceId,
    Map<String, Object> evidenceSummary,
    Instant completedAt
) {
}
