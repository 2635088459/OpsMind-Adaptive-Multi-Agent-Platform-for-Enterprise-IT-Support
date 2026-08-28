package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.ServiceIdentityRepository;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataServiceIdentityJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.ServiceIdentityMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SPEC-UA-002. Replaces the SPEC-UA-001-scoped {@code InMemoryServiceIdentityRepository}. */
@Component
public class ServiceIdentityPersistenceAdapter implements ServiceIdentityRepository {

    private final SpringDataServiceIdentityJpaRepository repository;

    public ServiceIdentityPersistenceAdapter(SpringDataServiceIdentityJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ServiceIdentity> findById(String serviceIdentityId) {
        return repository.findById(serviceIdentityId).map(ServiceIdentityMapper::toDomain);
    }

    @Override
    public Optional<ServiceIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject) {
        return repository.findByTenantIdAndIssuerAndSubject(tenantId, externalSubject.issuer(), externalSubject.subject())
            .map(ServiceIdentityMapper::toDomain);
    }

    @Override
    public List<ServiceIdentity> findActiveExpired(Instant now) {
        return repository.findByStatusAndValidUntilLessThanEqual("ACTIVE", now).stream().map(ServiceIdentityMapper::toDomain).toList();
    }

    @Override
    public ServiceIdentity save(ServiceIdentity serviceIdentity) {
        repository.save(ServiceIdentityMapper.toEntity(serviceIdentity));
        return serviceIdentity;
    }
}
