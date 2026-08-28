package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.BreakGlassGrantJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SpringDataBreakGlassGrantJpaRepository extends JpaRepository<BreakGlassGrantJpaEntity, String> {

    List<BreakGlassGrantJpaEntity> findByTenantIdAndIssuerAndSubject(String tenantId, String issuer, String subject);

    List<BreakGlassGrantJpaEntity> findByStatusAndExpiresAtLessThanEqual(String status, Instant now);

    List<BreakGlassGrantJpaEntity> findByApprovalReferenceOrderByCreatedAtAsc(String approvalReference);
}
