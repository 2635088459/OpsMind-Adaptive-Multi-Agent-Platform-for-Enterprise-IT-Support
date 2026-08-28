package com.opsmind.identity.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * SPEC-UA-028. The shared envelope every consumed event carries
 * (06-event-contracts §Envelope) — the exact wire shape
 * policy-approval-governance-service's own {@code
 * OutboxDispatchService#buildPayload} writes for every event it publishes
 * (verified against that service's own real code, not assumed): {@code
 * eventId, eventType, producer, schemaVersion, aggregateId, correlationId,
 * causationId, occurredAt, payload}. PG's own {@code ticketId} envelope
 * field is conditional (only present when the approval is ticket-sourced)
 * and this domain never needs it — {@code
 * @JsonIgnoreProperties(ignoreUnknown = true)} is required so a real
 * ticket-linked approval event does not fail to parse: this app's own
 * Jackson configuration defaults {@code FAIL_ON_UNKNOWN_PROPERTIES} to
 * {@code true} (confirmed via a real Testcontainers RabbitMQ round trip),
 * never assumed to be lenient by default again.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsumedEventEnvelope(
    String eventId,
    String eventType,
    String producer,
    int schemaVersion,
    String aggregateId,
    String correlationId,
    String causationId,
    Instant occurredAt,
    Map<String, Object> payload
) {
}
