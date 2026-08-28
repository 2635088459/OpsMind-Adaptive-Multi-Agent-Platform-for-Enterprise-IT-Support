package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.AuditIntegrityPort;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataIdentityAuditRecordJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.IdentityAuditRecordMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * SPEC-UA-003; SPEC-UA-031 added the real hash-chain sealing here (not in
 * any of the ~7 application services that call {@link #record}) so every
 * caller gets the guarantee for free with zero call-site changes — this
 * adapter alone knows "what was the previous write" for a given tenant.
 * Replaces the SPEC-UA-001-scoped {@code InMemoryAuditPort}.
 */
@Component
public class IdentityAuditPersistenceAdapter implements AuditPort {

    private final SpringDataIdentityAuditRecordJpaRepository repository;
    private final AuditIntegrityPort auditIntegrityPort;

    public IdentityAuditPersistenceAdapter(SpringDataIdentityAuditRecordJpaRepository repository, AuditIntegrityPort auditIntegrityPort) {
        this.repository = repository;
        this.auditIntegrityPort = auditIntegrityPort;
    }

    @Override
    public IdentityAuditRecord record(IdentityAuditRecord record) {
        String previousHash = findMostRecentRecordHash(record.tenantId()).orElse(null);
        IdentityAuditRecord unsealed = record.withHashes(previousHash, null);
        String recordHash = auditIntegrityPort.computeRecordHash(unsealed);
        IdentityAuditRecord sealed = record.withHashes(previousHash, recordHash);
        repository.save(IdentityAuditRecordMapper.toEntity(sealed));
        return sealed;
    }

    @Override
    public List<IdentityAuditRecord> findByCorrelationId(String correlationId) {
        return repository.findByCorrelationId(correlationId).stream().map(IdentityAuditRecordMapper::toDomain).toList();
    }

    @Override
    public Optional<String> findMostRecentRecordHash(TenantId tenantId) {
        return repository.findFirstByTenantIdOrderByOccurredAtDesc(tenantId.value()).map(entity -> entity.getRecordHash());
    }
}
