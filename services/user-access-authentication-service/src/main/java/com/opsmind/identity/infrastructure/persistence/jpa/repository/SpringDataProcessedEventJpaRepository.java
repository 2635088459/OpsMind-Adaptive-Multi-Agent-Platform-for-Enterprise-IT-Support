package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.ProcessedEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface SpringDataProcessedEventJpaRepository extends JpaRepository<ProcessedEventJpaEntity, UUID> {

    /**
     * A dedicated {@code REQUIRES_NEW} transaction: relies on the real
     * {@code uq_processed_events_event_consumer} database constraint (a
     * caught unique-violation flushed here must not poison a caller's own
     * wider transaction) — mirrors policy-approval-governance-service's own
     * {@code SpringDataProcessedEventJpaRepository#insertIsolated}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    default void insertIsolated(ProcessedEventJpaEntity entity) {
        save(entity);
        flush();
    }
}
