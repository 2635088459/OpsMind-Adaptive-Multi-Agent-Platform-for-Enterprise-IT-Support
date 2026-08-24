package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC-UA-001-scoped placeholder: in-memory, not backed by Postgres — state
 * does not survive a restart and is not shared across replicas. Real JPA
 * entity/mapper/adapter persistence under {@code infrastructure.persistence.jpa}
 * is SPEC-UA-002's job (Identity Schema Baseline).
 */
@Repository
public class InMemoryUserIdentityRepository implements UserIdentityRepository {

    private final Map<String, UserIdentity> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<UserIdentity> findById(String userIdentityId) {
        return Optional.ofNullable(byId.get(userIdentityId));
    }

    @Override
    public Optional<UserIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject) {
        return byId.values().stream()
            .filter(u -> u.tenantId().value().equals(tenantId) && u.externalSubject().equals(externalSubject))
            .findFirst();
    }

    @Override
    public UserIdentity save(UserIdentity userIdentity) {
        byId.put(userIdentity.userIdentityId(), userIdentity);
        return userIdentity;
    }
}
