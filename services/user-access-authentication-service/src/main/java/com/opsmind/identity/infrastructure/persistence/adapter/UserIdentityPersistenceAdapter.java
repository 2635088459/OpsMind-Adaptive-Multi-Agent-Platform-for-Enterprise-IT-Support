package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataUserIdentityJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.UserIdentityMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SPEC-UA-002. Replaces the SPEC-UA-001-scoped {@code InMemoryUserIdentityRepository}. */
@Component
public class UserIdentityPersistenceAdapter implements UserIdentityRepository {

    private final SpringDataUserIdentityJpaRepository repository;

    public UserIdentityPersistenceAdapter(SpringDataUserIdentityJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserIdentity> findById(String userIdentityId) {
        return repository.findById(userIdentityId).map(UserIdentityMapper::toDomain);
    }

    @Override
    public Optional<UserIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject) {
        return repository.findByTenantIdAndIssuerAndSubject(tenantId, externalSubject.issuer(), externalSubject.subject())
            .map(UserIdentityMapper::toDomain);
    }

    @Override
    public UserIdentity save(UserIdentity userIdentity) {
        repository.save(UserIdentityMapper.toEntity(userIdentity));
        return userIdentity;
    }

    @Override
    public List<UserIdentity> findDeprovisionedDueForPiiRedaction(Instant cutoff) {
        return repository.findByDeprovisionedAtNotNullAndPiiRedactedAtIsNullAndDeprovisionedAtLessThanEqual(cutoff).stream()
            .map(UserIdentityMapper::toDomain)
            .toList();
    }
}
