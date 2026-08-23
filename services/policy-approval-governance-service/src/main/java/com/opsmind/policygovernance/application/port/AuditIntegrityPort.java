package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;

/** Port that computes a tamper-evident fingerprint for a {@link GovernanceAuditRecord} before it is persisted. */
public interface AuditIntegrityPort {

    /** Computes a hash over {@code record}'s fact fields; {@code record.integrityHash()} is ignored as input. */
    String computeIntegrityHash(GovernanceAuditRecord record);
}
