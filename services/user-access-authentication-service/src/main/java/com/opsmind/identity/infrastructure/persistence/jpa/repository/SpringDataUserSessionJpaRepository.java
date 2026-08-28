package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.UserSessionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataUserSessionJpaRepository extends JpaRepository<UserSessionJpaEntity, String> {

    List<UserSessionJpaEntity> findByStatusAndExpiresAtLessThanEqual(String status, Instant now);

    Optional<UserSessionJpaEntity> findFirstByStatusAndIdpSessionIdHash(String status, String idpSessionIdHash);

    List<UserSessionJpaEntity> findByStatusAndEndSessionNotifiedAtIsNull(String status);

    List<UserSessionJpaEntity> findByStatus(String status);

    boolean existsByTokenIdHash(String tokenIdHash);
}
