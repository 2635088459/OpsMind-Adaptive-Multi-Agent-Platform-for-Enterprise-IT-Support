package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.BreakGlassGrantRepository;
import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataBreakGlassGrantJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.BreakGlassGrantMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class BreakGlassGrantPersistenceAdapter implements BreakGlassGrantRepository {

    private final SpringDataBreakGlassGrantJpaRepository repository;

    public BreakGlassGrantPersistenceAdapter(SpringDataBreakGlassGrantJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<BreakGlassGrant> findById(String breakGlassGrantId) {
        return repository.findById(breakGlassGrantId).map(BreakGlassGrantMapper::toDomain);
    }

    @Override
    public List<BreakGlassGrant> findByExternalSubject(String tenantId, ExternalSubject externalSubject) {
        return repository.findByTenantIdAndIssuerAndSubject(tenantId, externalSubject.issuer(), externalSubject.subject())
            .stream().map(BreakGlassGrantMapper::toDomain).toList();
    }

    @Override
    public List<BreakGlassGrant> findActiveExpired(Instant now) {
        return repository.findByStatusAndExpiresAtLessThanEqual("ACTIVE", now).stream().map(BreakGlassGrantMapper::toDomain).toList();
    }

    @Override
    public List<BreakGlassGrant> findByApprovalReference(String approvalReference) {
        return repository.findByApprovalReferenceOrderByCreatedAtAsc(approvalReference).stream().map(BreakGlassGrantMapper::toDomain).toList();
    }

    @Override
    public BreakGlassGrant save(BreakGlassGrant grant) {
        repository.save(BreakGlassGrantMapper.toEntity(grant));
        return grant;
    }
}
