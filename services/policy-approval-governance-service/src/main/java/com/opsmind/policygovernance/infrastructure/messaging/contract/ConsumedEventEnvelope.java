package com.opsmind.policygovernance.infrastructure.messaging.contract;

import java.time.Instant;
import java.util.Map;

/**
 * The shared envelope every consumed event carries (06-event-contracts
 * §Envelope) — the same shape {@code
 * application.OutboxDispatchService#buildPayload} writes for every event
 * this service publishes, read back here for the events this service
 * consumes. {@code payload} stays a raw {@code Map} at this layer; each
 * consumer converts it to its own event-specific payload record via {@code
 * ObjectMapper#convertValue} (mirrors ticket-workflow-service's own {@code
 * ConsumedEventEnvelope}/per-event-payload split).
 */
public record ConsumedEventEnvelope(
    String eventId,
    String eventType,
    String producer,
    int schemaVersion,
    String aggregateId,
    String ticketId,
    String correlationId,
    String causationId,
    Instant occurredAt,
    Map<String, Object> payload
) {
}
