package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Mapped from a trusted, schema- and producer-validated {@code verification.completed.v1} event (result = SUCCESS) by the messaging infrastructure layer. */
public record ApplyVerificationSuccessCommand(
    TicketId ticketId,
    String eventId,
    String verificationId,
    String workflowId,
    UUID resolutionCycleId,
    int attemptNumber,
    String verificationEvidenceId,
    Map<String, Object> evidenceSummary,
    Instant completedAt,
    String traceId,
    String correlationId
) {
}
