package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * The consumer-side subset of the canonical event envelope (06-event-contracts
 * §5-6) that Ticket Workflow's approval consumers actually depend on.
 * {@code ignoreUnknown = true} deliberately mirrors the compatibility rule
 * that additive envelope fields (a future Minor version) must not break
 * deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsumedEventEnvelope(
    String eventId,
    String eventType,
    String eventVersion,
    Instant occurredAt,
    String producer,
    String traceId,
    String correlationId,
    String causationId,
    String ticketId,
    String workflowId,
    String dataClassification,
    Map<String, Object> payload
) {
}
