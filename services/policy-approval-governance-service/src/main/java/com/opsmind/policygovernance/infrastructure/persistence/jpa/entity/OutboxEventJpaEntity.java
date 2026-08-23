package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "governance", name = "outbox_events")
public class OutboxEventJpaEntity {

    @Id
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private String eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers_json", nullable = false, columnDefinition = "jsonb")
    private String headersJson;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "causation_id")
    private String causationId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OutboxEventJpaEntity() {
    }

    public OutboxEventJpaEntity(
        UUID outboxId, String aggregateType, String aggregateId, String eventType, String eventVersion,
        String payloadJson, String headersJson, String correlationId, String causationId, String status,
        int attemptCount, Instant availableAt, Instant publishedAt, Instant occurredAt
    ) {
        this.outboxId = outboxId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payloadJson = payloadJson;
        this.headersJson = headersJson;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.status = status;
        this.attemptCount = attemptCount;
        this.availableAt = availableAt;
        this.publishedAt = publishedAt;
        this.occurredAt = occurredAt;
    }

    public UUID getOutboxId() {
        return outboxId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventVersion() {
        return eventVersion;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getHeadersJson() {
        return headersJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public String getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
