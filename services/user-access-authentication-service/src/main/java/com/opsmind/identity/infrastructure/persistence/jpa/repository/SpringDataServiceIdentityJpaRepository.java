package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.ServiceIdentityJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataServiceIdentityJpaRepository extends JpaRepository<ServiceIdentityJpaEntity, String> {

    Optional<ServiceIdentityJpaEntity> findByTenantIdAndIssuerAndSubject(String tenantId, String issuer, String subject);

    List<ServiceIdentityJpaEntity> findByStatusAndValidUntilLessThanEqual(String status, Instant now);
}
