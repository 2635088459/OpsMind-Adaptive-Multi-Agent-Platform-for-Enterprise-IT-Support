package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.UserIdentityJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataUserIdentityJpaRepository extends JpaRepository<UserIdentityJpaEntity, String> {

    Optional<UserIdentityJpaEntity> findByTenantIdAndIssuerAndSubject(String tenantId, String issuer, String subject);

    List<UserIdentityJpaEntity> findByDeprovisionedAtNotNullAndPiiRedactedAtIsNullAndDeprovisionedAtLessThanEqual(Instant cutoff);
}
