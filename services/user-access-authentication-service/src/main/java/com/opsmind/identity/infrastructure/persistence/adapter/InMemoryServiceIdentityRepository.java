package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.ServiceIdentityRepository;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** SPEC-UA-001-scoped placeholder — see {@link InMemoryUserIdentityRepository}'s own javadoc for the deferral this mirrors. */
@Repository
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
    public ServiceIdentity save(ServiceIdentity serviceIdentity) {
        byId.put(serviceIdentity.serviceIdentityId(), serviceIdentity);
        return serviceIdentity;
    }
}
