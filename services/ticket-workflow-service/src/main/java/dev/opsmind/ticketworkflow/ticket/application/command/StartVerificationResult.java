package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record StartVerificationResult(
    TicketId ticketId,
    String verificationId,
    UUID resolutionCycleId,
    String workflowId,
    String toolResultId,
    int attemptNumber,
    String verificationType,
    TicketStatus previousStatus,
    TicketStatus status,
    String startedBy,
    Instant startedAt,
    long version,
    boolean replayed
) {
}
