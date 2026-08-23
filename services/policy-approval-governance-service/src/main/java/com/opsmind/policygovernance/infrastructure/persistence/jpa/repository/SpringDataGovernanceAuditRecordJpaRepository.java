package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.GovernanceAuditRecordJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataGovernanceAuditRecordJpaRepository extends JpaRepository<GovernanceAuditRecordJpaEntity, String> {

    List<GovernanceAuditRecordJpaEntity> findByCorrelationId(String correlationId);
}
