package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.AuthorizationDecisionRepository;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataAuthorizationDecisionJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.AuthorizationDecisionMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** SPEC-UA-002. Replaces the SPEC-UA-001-scoped {@code InMemoryAuthorizationDecisionRepository}. */
@Component
public class AuthorizationDecisionPersistenceAdapter implements AuthorizationDecisionRepository {

    private final SpringDataAuthorizationDecisionJpaRepository repository;

    public AuthorizationDecisionPersistenceAdapter(SpringDataAuthorizationDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AuthorizationDecision> findByDecisionKeyAndInputHash(String decisionKey, String inputHash) {
        return repository.findByDecisionKeyAndInputHash(decisionKey, inputHash).map(AuthorizationDecisionMapper::toDomain);
    }

    @Override
    public Optional<AuthorizationDecision> findById(String decisionId) {
        return repository.findById(decisionId).map(AuthorizationDecisionMapper::toDomain);
    }

    @Override
    public AuthorizationDecision save(AuthorizationDecision decision) {
        repository.save(AuthorizationDecisionMapper.toEntity(decision));
        return decision;
    }
}
