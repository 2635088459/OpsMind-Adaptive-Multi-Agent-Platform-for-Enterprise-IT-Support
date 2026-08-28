package com.opsmind.identity.infrastructure.persistence.mapper;

import com.opsmind.identity.application.model.OutboxEventRecord;
import com.opsmind.identity.application.model.OutboxEventStatus;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.OutboxEventJpaEntity;

import java.util.UUID;

public final class OutboxEventMapper {

    private OutboxEventMapper() {
    }

    public static OutboxEventJpaEntity toEntity(OutboxEventRecord record) {
        return new OutboxEventJpaEntity(
            UUID.fromString(record.outboxId()), record.aggregateType(), record.aggregateId(), record.eventType(), record.eventVersion(),
            record.payloadJson(), record.correlationId(), record.status().name(), record.attemptCount(), record.availableAt(),
            record.publishedAt(), record.occurredAt()
        );
    }

    public static OutboxEventRecord toDomain(OutboxEventJpaEntity entity) {
        return new OutboxEventRecord(
            entity.getOutboxId().toString(), entity.getAggregateType(), entity.getAggregateId(), entity.getEventType(),
            entity.getEventVersion(), entity.getPayloadJson(), entity.getCorrelationId(), OutboxEventStatus.valueOf(entity.getStatus()),
            entity.getAttemptCount(), entity.getAvailableAt(), entity.getPublishedAt(), entity.getOccurredAt()
        );
    }
}
