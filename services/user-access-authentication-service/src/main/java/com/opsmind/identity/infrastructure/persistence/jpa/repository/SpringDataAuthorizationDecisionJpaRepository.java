package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.AuthorizationDecisionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataAuthorizationDecisionJpaRepository extends JpaRepository<AuthorizationDecisionJpaEntity, String> {

    Optional<AuthorizationDecisionJpaEntity> findByDecisionKeyAndInputHash(String decisionKey, String inputHash);
}
