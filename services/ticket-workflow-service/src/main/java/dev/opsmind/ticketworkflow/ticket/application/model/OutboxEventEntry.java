package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxEventEntry(
    UUID outboxId,
    String eventId,
    String eventType,
    String eventVersion,
    String routingKey,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    TicketId ticketId,
    String workflowId,
    String traceId,
    String correlationId,
    String causationId,
    String dataClassification,
    Map<String, Object> payload,
    Instant createdAt,
    Instant availableAt
) {
}
