package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** SPEC-TW-024 event-contract / 06-event-contracts CON-014 {@code verification.completed}-failure-branch shape, split into its own event type in this codebase (see {@code VerificationFailureEventConsumer}'s Javadoc). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationFailureEventPayload(
    String workflowId,
    UUID resolutionCycleId,
    String verificationId,
    int attemptNumber,
    String failureCode,
    String failureClass,
    Boolean unsafe,
    Instant failedAt
) {
}
