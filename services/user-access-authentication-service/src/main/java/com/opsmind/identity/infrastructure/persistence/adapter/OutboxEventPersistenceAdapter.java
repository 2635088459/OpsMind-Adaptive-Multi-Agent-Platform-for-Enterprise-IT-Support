package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.model.OutboxEventRecord;
import com.opsmind.identity.application.port.out.OutboxEventRepository;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataOutboxEventJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.OutboxEventMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SPEC-UA-003. The durable half of the transactional outbox (08-transaction-and-outbox).
 * {@link #append} rides on {@code JpaRepository#save}'s own transaction (the
 * caller's own business transaction); the {@code @Modifying} status-update
 * queries need their own boundary when invoked without one already open —
 * {@link com.opsmind.identity.application.service.OutboxDispatchService}
 * already provides one in production.
 */
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
        return repository.findDuePending(now, PageRequest.of(0, limit)).stream().map(OutboxEventMapper::toDomain).toList();
    }

    @Override
    @Transactional
    public void markPublished(String outboxId, Instant publishedAt) {
        repository.markPublished(UUID.fromString(outboxId), publishedAt);
    }

    @Override
    @Transactional
    public void markRetry(String outboxId, int attemptCount, Instant nextAvailableAt) {
        repository.markRetry(UUID.fromString(outboxId), attemptCount, nextAvailableAt);
    }

    @Override
    @Transactional
    public void markFailed(String outboxId) {
        repository.markFailed(UUID.fromString(outboxId));
    }

    @Override
    public long countPending() {
        return repository.countByStatus("PENDING");
    }
}
