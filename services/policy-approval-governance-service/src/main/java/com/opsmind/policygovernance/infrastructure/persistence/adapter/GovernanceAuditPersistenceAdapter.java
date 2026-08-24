package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataGovernanceAuditRecordJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.GovernanceAuditRecordJpaEntity;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.GovernanceAuditRecordMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    @Override
    public List<GovernanceAuditRecord> findByTicketId(String ticketId) {
        return repository.findByTicketId(ticketId).stream().map(GovernanceAuditRecordMapper::toDomain).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findByApprovalRequestId(String approvalRequestId) {
        return repository.findByApprovalRequestId(approvalRequestId).stream().map(GovernanceAuditRecordMapper::toDomain).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findByPolicyDecisionId(String policyDecisionId) {
        return repository.findByPolicyDecisionId(policyDecisionId).stream().map(GovernanceAuditRecordMapper::toDomain).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findBySourceRequestId(String sourceRequestId) {
        return repository.findBySourceRequestId(sourceRequestId).stream().map(GovernanceAuditRecordMapper::toDomain).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findByPolicyId(String policyId) {
        return repository.findByPolicyId(policyId).stream().map(GovernanceAuditRecordMapper::toDomain).toList();
    }

    @Override
    public Optional<String> findMostRecentIntegrityHash() {
        return repository.findFirstByOrderByRecordedAtDesc().map(GovernanceAuditRecordJpaEntity::getIntegrityHash);
    }

    @Override
    public List<GovernanceAuditRecord> findAllOrderedByRecordedAt() {
        return repository.findAllByOrderByRecordedAtAsc().stream().map(GovernanceAuditRecordMapper::toDomain).toList();
    }

    /**
     * SPEC-PG-031: {@code @Modifying} custom queries need their own
     * transactional boundary when invoked without one already open —
     * mirrors {@code OutboxEventPersistenceAdapter#requeue}'s own javadoc
     * for exactly the same reason.
     */
    @Override
    @Transactional
    public int archiveRecordedBefore(Instant cutoff, Instant archivedAt) {
        return repository.archiveRecordedBefore(cutoff, archivedAt);
    }
}
