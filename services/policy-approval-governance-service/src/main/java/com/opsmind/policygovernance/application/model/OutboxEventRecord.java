package com.opsmind.policygovernance.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A durable row in {@code outbox_events} (08-transaction-and-outbox):
 * {@link com.opsmind.policygovernance.application.OutboxDispatchService#stage}
 * inserts it in the same transaction as the governance fact it describes;
 * {@link com.opsmind.policygovernance.application.OutboxDispatchService#publishPending}
 * later reads {@code PENDING} rows and hands them to the real message
 * broker, tracking attempts/backoff/dead-letter here rather than in memory.
 */
public record OutboxEventRecord(
    String outboxId,
    String aggregateType,
    String aggregateId,
    String eventType,
    String eventVersion,
    String payloadJson,
    String correlationId,
    String causationId,
    OutboxEventStatus status,
    int attemptCount,
    Instant availableAt,
    Instant publishedAt,
    Instant occurredAt
) {
    public OutboxEventRecord {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventVersion, "eventVersion");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
