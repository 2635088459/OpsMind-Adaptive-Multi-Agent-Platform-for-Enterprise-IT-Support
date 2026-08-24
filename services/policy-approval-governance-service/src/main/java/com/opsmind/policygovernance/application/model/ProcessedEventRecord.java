package com.opsmind.policygovernance.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A durable row in {@code processed_events} (06-event-contracts
 * §Idempotency: "Every consumer deduplicates by {@code eventId +
 * consumerName}"). SPEC-PG-034 (goal: "admin-safe repair flow for
 * governance event replay/backfill") is this type's own reason to exist —
 * before this spec, {@code ProcessedEventRepository} only ever wrote this
 * table ({@link com.opsmind.policygovernance.application.ConsumedEventDeduplicationService}),
 * never read it back as a value an operator could actually see.
 */
public record ProcessedEventRecord(String eventId, String consumerName, String eventType, Instant processedAt) {

    public ProcessedEventRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(processedAt, "processedAt");
    }
}
