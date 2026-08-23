package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.port.OutboxEventRepository;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataOutboxEventJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.OutboxEventMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxEventPersistenceAdapter implements OutboxEventRepository {

    private final SpringDataOutboxEventJpaRepository repository;

    public OutboxEventPersistenceAdapter(SpringDataOutboxEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(OutboxEventRecord record) {
        repository.save(OutboxEventMapper.toEntity(record));
    }

    @Override
    public List<OutboxEventRecord> findPendingBatch(Instant now, int limit) {
        return repository.findDuePending(now, PageRequest.of(0, limit)).stream()
            .map(OutboxEventMapper::toDomain)
            .toList();
    }

    @Override
    public void markPublished(String outboxId, Instant publishedAt) {
        repository.markPublished(UUID.fromString(outboxId), publishedAt);
    }

    @Override
    public void markRetry(String outboxId, int attemptCount, Instant nextAvailableAt) {
        repository.markRetry(UUID.fromString(outboxId), attemptCount, nextAvailableAt);
    }

    @Override
    public void markFailed(String outboxId) {
        repository.markFailed(UUID.fromString(outboxId));
    }

    @Override
    public long countPending() {
        return repository.countByStatus("PENDING");
    }
}
