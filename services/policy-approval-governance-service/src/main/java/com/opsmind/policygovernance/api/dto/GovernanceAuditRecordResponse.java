package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;

import java.time.Instant;

/** api-contract: "Audit API returns metadata/hash by default, not sensitive raw input." No raw-input field exists to leak. */
public record GovernanceAuditRecordResponse(
    String auditRecordId,
    GovernanceAuditRecord.Action action,
    String actorId,
    String sourceDomain,
    String sourceRequestId,
    String policyId,
    String policyVersion,
    String reason,
    String correlationId,
    String integrityHash,
    Instant recordedAt
) {

    public static GovernanceAuditRecordResponse from(GovernanceAuditRecord record) {
        return new GovernanceAuditRecordResponse(
            record.auditRecordId(), record.action(), record.actorId(), record.sourceDomain(), record.sourceRequestId(),
            record.policyId(), record.policyVersion(), record.reason(), record.correlationId(),
            record.integrityHash(), record.recordedAt()
        );
    }
}
