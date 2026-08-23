package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;

import java.util.List;

/** Port for append-only {@link GovernanceAuditRecord} persistence — never updated or deleted (INV-PG-008). */
public interface GovernanceAuditRepository {

    GovernanceAuditRecord append(GovernanceAuditRecord record);

    List<GovernanceAuditRecord> findByCorrelationId(String correlationId);
}
