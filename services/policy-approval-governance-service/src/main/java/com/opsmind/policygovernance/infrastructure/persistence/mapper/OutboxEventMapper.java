package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.OutboxEventJpaEntity;

import java.util.UUID;

public final class OutboxEventMapper {

    private OutboxEventMapper() {
    }

    public static OutboxEventJpaEntity toEntity(OutboxEventRecord record) {
        return new OutboxEventJpaEntity(
            UUID.fromString(record.outboxId()), record.aggregateType(), record.aggregateId(), record.eventType(),
            record.eventVersion(), record.payloadJson(), "{}", record.correlationId(), record.causationId(),
            record.status().name(), record.attemptCount(), record.availableAt(), record.publishedAt(), record.occurredAt()
        );
    }

    public static OutboxEventRecord toDomain(OutboxEventJpaEntity entity) {
        return new OutboxEventRecord(
            entity.getOutboxId().toString(), entity.getAggregateType(), entity.getAggregateId(), entity.getEventType(),
            entity.getEventVersion(), entity.getPayloadJson(), entity.getCorrelationId(), entity.getCausationId(),
            OutboxEventStatus.valueOf(entity.getStatus()), entity.getAttemptCount(), entity.getAvailableAt(),
            entity.getPublishedAt(), entity.getOccurredAt()
        );
    }
}
