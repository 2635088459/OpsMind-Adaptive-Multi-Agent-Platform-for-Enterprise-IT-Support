package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;

public record TicketVerificationSuccessUpdate(
    TicketId ticketId,
    long expectedVersion,
    String verificationId,
    String verificationEvidenceId,
    Map<String, Object> evidenceSummary,
    Instant completedAt,
    String completedEventId,
    Instant updatedAt
) {
}
