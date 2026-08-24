package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "governance", name = "processed_events")
public class ProcessedEventJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "consumer_name", nullable = false)
    private String consumerName;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventJpaEntity() {
    }

    public ProcessedEventJpaEntity(UUID id, String eventId, String consumerName, String eventType, Instant processedAt) {
        this.id = id;
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
