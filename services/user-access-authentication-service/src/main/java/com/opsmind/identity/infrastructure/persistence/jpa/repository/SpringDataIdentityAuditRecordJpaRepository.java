package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.IdentityAuditRecordJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataIdentityAuditRecordJpaRepository extends JpaRepository<IdentityAuditRecordJpaEntity, String> {

    List<IdentityAuditRecordJpaEntity> findByCorrelationId(String correlationId);

    /** SPEC-UA-031: the chain's own current tip for one tenant, by {@code occurredAt} — the same field the hash itself is computed over, so it is a stable enough proxy for insertion order given this codebase's own single-writer-per-request model. */
    Optional<IdentityAuditRecordJpaEntity> findFirstByTenantIdOrderByOccurredAtDesc(String tenantId);
}
