package dev.opsmind.ticketworkflow.ticket.application.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record IdempotencyReservationRequest(
    UUID idempotencyRecordId,
    String actorScope,
    String idempotencyKey,
    String operationId,
    String requestHash,
    Instant now,
    Duration ttl,
    Duration staleThreshold
) {
}
