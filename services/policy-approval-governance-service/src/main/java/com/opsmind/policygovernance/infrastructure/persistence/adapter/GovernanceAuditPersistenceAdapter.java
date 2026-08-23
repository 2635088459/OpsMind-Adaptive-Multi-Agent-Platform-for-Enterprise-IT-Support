package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataGovernanceAuditRecordJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.GovernanceAuditRecordMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GovernanceAuditPersistenceAdapter implements GovernanceAuditRepository {

    private final SpringDataGovernanceAuditRecordJpaRepository repository;

    public GovernanceAuditPersistenceAdapter(SpringDataGovernanceAuditRecordJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public GovernanceAuditRecord append(GovernanceAuditRecord record) {
        repository.save(GovernanceAuditRecordMapper.toEntity(record));
        return record;
    }

    @Override
    public List<GovernanceAuditRecord> findByCorrelationId(String correlationId) {
        return repository.findByCorrelationId(correlationId).stream()
            .map(GovernanceAuditRecordMapper::toDomain)
            .toList();
    }
}
