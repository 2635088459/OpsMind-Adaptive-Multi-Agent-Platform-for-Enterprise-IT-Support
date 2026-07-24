package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "outbox_events")
public class OutboxEventJpaEntity {

    @Id
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private String eventVersion;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "aggregate_version")
    private Long aggregateVersion;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "trace_id", nullable = false)
    private String traceId;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "causation_id")
    private String causationId;

    @Column(name = "data_classification", nullable = false)
    private String dataClassification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false)
    private Map<String, Object> headers;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    protected OutboxEventJpaEntity() {
    }

    public OutboxEventJpaEntity(
        UUID outboxId,
        String eventId,
        String eventType,
        String eventVersion,
        String routingKey,
        String aggregateType,
        String aggregateId,
        Long aggregateVersion,
        UUID ticketId,
        String workflowId,
        String traceId,
        String correlationId,
        String causationId,
        String dataClassification,
        Map<String, Object> payload,
        Map<String, Object> headers,
        Instant createdAt,
        Instant availableAt
    ) {
        this.outboxId = outboxId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.routingKey = routingKey;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.ticketId = ticketId;
        this.workflowId = workflowId;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.dataClassification = dataClassification;
        this.payload = payload;
        this.headers = headers == null ? Map.of() : headers;
        this.createdAt = createdAt;
        this.availableAt = availableAt;
        this.publishAttempts = 0;
    }

    public UUID getOutboxId() {
        return outboxId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventVersion() {
        return eventVersion;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public Long getAggregateVersion() {
        return aggregateVersion;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public String getDataClassification() {
        return dataClassification;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }
}
