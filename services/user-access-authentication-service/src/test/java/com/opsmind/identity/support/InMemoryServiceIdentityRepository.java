package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.ServiceIdentityRepository;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.domain.workload.ServiceIdentityStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, dependency-free application-service unit-test double for {@link ServiceIdentityRepository}. Real persistence is {@code ServiceIdentityPersistenceAdapter} (SPEC-UA-002). */
public class InMemoryServiceIdentityRepository implements ServiceIdentityRepository {

    private final Map<String, ServiceIdentity> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<ServiceIdentity> findById(String serviceIdentityId) {
        return Optional.ofNullable(byId.get(serviceIdentityId));
    }

    @Override
    public Optional<ServiceIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject) {
        return byId.values().stream()
            .filter(s -> s.tenantId().value().equals(tenantId) && s.externalSubject().equals(externalSubject))
            .findFirst();
    }

    @Override
    public List<ServiceIdentity> findActiveExpired(Instant now) {
        return byId.values().stream()
            .filter(s -> s.status() == ServiceIdentityStatus.ACTIVE && s.validUntil() != null && !now.isBefore(s.validUntil()))
            .toList();
    }

    @Override
    public ServiceIdentity save(ServiceIdentity serviceIdentity) {
        byId.put(serviceIdentity.serviceIdentityId(), serviceIdentity);
        return serviceIdentity;
    }
}
