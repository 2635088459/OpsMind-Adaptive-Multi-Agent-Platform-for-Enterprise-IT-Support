package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.opsmind.policygovernance.application.model.ProcessedEventRecord;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.ProcessedEventJpaEntity;

public final class ProcessedEventMapper {

    private ProcessedEventMapper() {
    }

    public static ProcessedEventRecord toDomain(ProcessedEventJpaEntity entity) {
        return new ProcessedEventRecord(entity.getEventId(), entity.getConsumerName(), entity.getEventType(), entity.getProcessedAt());
    }
}
