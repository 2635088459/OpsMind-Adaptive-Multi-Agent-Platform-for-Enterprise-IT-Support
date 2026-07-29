package dev.opsmind.ticketworkflow.ticket.api.support;

import java.time.Instant;
import java.util.UUID;

public record TriageTicketResponse(
    UUID ticketId,
    String status,
    UUID categoryId,
    UUID subcategoryId,
    String priority,
    UUID supportQueueId,
    String triagedBy,
    Instant triagedAt,
    long version
) {
}
