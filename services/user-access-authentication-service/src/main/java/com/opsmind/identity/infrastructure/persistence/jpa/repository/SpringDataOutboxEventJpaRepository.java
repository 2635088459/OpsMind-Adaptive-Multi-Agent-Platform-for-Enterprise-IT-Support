package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.OutboxEventJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    @Query("""
        SELECT o FROM OutboxEventJpaEntity o WHERE o.status = 'PENDING' AND (o.availableAt IS NULL OR o.availableAt <= :now)
        ORDER BY o.occurredAt ASC
        """)
    List<OutboxEventJpaEntity> findDuePending(@Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity o SET o.status = 'PUBLISHED', o.publishedAt = :publishedAt WHERE o.outboxId = :id")
    void markPublished(@Param("id") UUID outboxId, @Param("publishedAt") Instant publishedAt);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity o SET o.attemptCount = :attemptCount, o.availableAt = :nextAvailableAt WHERE o.outboxId = :id")
    void markRetry(@Param("id") UUID outboxId, @Param("attemptCount") int attemptCount, @Param("nextAvailableAt") Instant nextAvailableAt);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity o SET o.status = 'FAILED' WHERE o.outboxId = :id")
    void markFailed(@Param("id") UUID outboxId);

    long countByStatus(String status);
}
