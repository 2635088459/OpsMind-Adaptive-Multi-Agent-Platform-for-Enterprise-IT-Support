package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketUserInputRequestUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID requestId,
    String prompt,
    List<String> requestedFields,
    String requestedByType,
    String requestedById,
    Instant requestedAt,
    String resumeStatus,
    Instant expiresAt,
    String correlationId,
    Instant updatedAt
) {
}
