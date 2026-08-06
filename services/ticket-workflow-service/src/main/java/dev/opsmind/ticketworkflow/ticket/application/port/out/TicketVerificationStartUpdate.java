package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

public record TicketVerificationStartUpdate(
    TicketId ticketId,
    long expectedVersion,
    String verificationId,
    UUID resolutionCycleId,
    String workflowId,
    String toolResultId,
    int attemptNumber,
    String verificationType,
    Instant startedAt,
    Instant updatedAt
) {
}
