package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository;

import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.IdempotencyRecordJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataIdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordJpaEntity, UUID> {

    Optional<IdempotencyRecordJpaEntity> findByActorScopeAndIdempotencyKey(String actorScope, String idempotencyKey);
}
