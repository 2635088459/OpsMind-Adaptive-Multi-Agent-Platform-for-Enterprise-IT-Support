package dev.opsmind.ticketworkflow.ticket.application.idempotency;

import java.time.Instant;

public record IdempotencyCompletion(
    String resourceType,
    String resourceId,
    int responseStatus,
    String responseBodyJson,
    Instant completedAt
) {
}
