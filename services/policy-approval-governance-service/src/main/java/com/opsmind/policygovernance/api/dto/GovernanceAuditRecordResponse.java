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
    Instant recordedAt,
    /** SPEC-PG-017: exposed alongside {@code integrityHash} so a caller can verify the hash chain (11-security §Tamper-Resistant Audit). */
    String previousHash,
    /** SPEC-PG-030: the three query-linkage ids (goal: "queries by ticket/source/decision/approval/policy") — nullable, see {@code GovernanceAuditRecord}'s own javadoc for which actions set which. */
    String ticketId,
    String approvalRequestId,
    String policyDecisionId,
    /** SPEC-PG-031: {@code null} until a retention run archives this record (11-security §Tamper-Resistant Audit). */
    Instant archivedAt
) {

    public static GovernanceAuditRecordResponse from(GovernanceAuditRecord record) {
        return new GovernanceAuditRecordResponse(
            record.auditRecordId(), record.action(), record.actorId(), record.sourceDomain(), record.sourceRequestId(),
            record.policyId(), record.policyVersion(), record.reason(), record.correlationId(),
            record.integrityHash(), record.recordedAt(), record.previousHash(),
            record.ticketId(), record.approvalRequestId(), record.policyDecisionId(), record.archivedAt()
        );
    }
}
