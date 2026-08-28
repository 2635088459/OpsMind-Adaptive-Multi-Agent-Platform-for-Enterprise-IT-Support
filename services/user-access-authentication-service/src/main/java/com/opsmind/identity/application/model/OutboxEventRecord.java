package com.opsmind.identity.application.model;

import java.time.Instant;

/** 07-data-model §outbox_events. */
public record OutboxEventRecord(
    String outboxId,
    String aggregateType,
    String aggregateId,
    String eventType,
    String eventVersion,
    String payloadJson,
    String correlationId,
    OutboxEventStatus status,
    int attemptCount,
    Instant availableAt,
    Instant publishedAt,
    Instant occurredAt
) {
}
