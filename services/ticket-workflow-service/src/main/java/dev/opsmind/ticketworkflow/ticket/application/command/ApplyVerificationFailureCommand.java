package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

/** Mapped from a trusted, schema- and producer-validated {@code verification.failed.v1} event by the messaging infrastructure layer. */
public record ApplyVerificationFailureCommand(
    TicketId ticketId,
    String eventId,
    String verificationId,
    String workflowId,
    UUID resolutionCycleId,
    int attemptNumber,
    String failureCode,
    String failureClass,
    boolean unsafeResult,
    Instant failedAt,
    String traceId,
    String correlationId
) {
}
