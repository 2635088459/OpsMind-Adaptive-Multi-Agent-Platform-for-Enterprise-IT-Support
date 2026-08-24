package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.OutboxEventJpaEntity;
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
        SELECT o FROM OutboxEventJpaEntity o
        WHERE o.status = 'PENDING' AND (o.availableAt IS NULL OR o.availableAt <= :now)
        ORDER BY o.occurredAt ASC
        """)
    List<OutboxEventJpaEntity> findDuePending(@Param("now") Instant now, Pageable pageable);

    long countByStatus(String status);

    /** SPEC-PG-033: "poison decision review" for outbox rows — see {@code application.port.OutboxEventRepository#findFailed}. */
    List<OutboxEventJpaEntity> findByStatus(String status);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity o SET o.status = 'PUBLISHED', o.publishedAt = :publishedAt WHERE o.outboxId = :id")
    void markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity o SET o.status = 'PENDING', o.attemptCount = :attemptCount, o.availableAt = :availableAt WHERE o.outboxId = :id")
    void markRetry(@Param("id") UUID id, @Param("attemptCount") int attemptCount, @Param("availableAt") Instant availableAt);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity o SET o.status = 'FAILED' WHERE o.outboxId = :id")
    void markFailed(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity o SET o.status = 'PENDING', o.attemptCount = 0, o.availableAt = :availableAt WHERE o.outboxId = :id")
    void requeue(@Param("id") UUID id, @Param("availableAt") Instant availableAt);
}
