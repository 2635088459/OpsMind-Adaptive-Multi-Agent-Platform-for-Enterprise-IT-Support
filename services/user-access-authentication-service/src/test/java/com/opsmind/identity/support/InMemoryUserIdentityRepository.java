package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, dependency-free application-service unit-test double for {@link UserIdentityRepository}. Real persistence is {@code UserIdentityPersistenceAdapter} (SPEC-UA-002). */
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

    @Override
    public List<UserIdentity> findDeprovisionedDueForPiiRedaction(Instant cutoff) {
        return byId.values().stream()
            .filter(u -> u.deprovisionedAt() != null && u.piiRedactedAt() == null && !u.deprovisionedAt().isAfter(cutoff))
            .toList();
    }
}
