package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.GovernanceAuditRecordJpaEntity;

public final class GovernanceAuditRecordMapper {

    private GovernanceAuditRecordMapper() {
    }

    public static GovernanceAuditRecordJpaEntity toEntity(GovernanceAuditRecord record) {
        return new GovernanceAuditRecordJpaEntity(
            record.auditRecordId(), record.action().name(), record.actorId(), record.sourceDomain(),
            record.sourceRequestId(), record.policyId(), record.policyVersion(), record.reason(),
            record.correlationId(), record.causationId(), record.integrityHash(), record.recordedAt(),
            record.previousHash(), record.ticketId(), record.approvalRequestId(), record.policyDecisionId(),
            record.archivedAt()
        );
    }

    public static GovernanceAuditRecord toDomain(GovernanceAuditRecordJpaEntity entity) {
        return new GovernanceAuditRecord(
            entity.getAuditRecordId(), GovernanceAuditRecord.Action.valueOf(entity.getAction()), entity.getActorId(),
            entity.getSourceDomain(), entity.getSourceRequestId(), entity.getPolicyId(), entity.getPolicyVersion(),
            entity.getReason(), entity.getCorrelationId(), entity.getCausationId(), entity.getIntegrityHash(),
            entity.getRecordedAt(), entity.getPreviousHash(), entity.getTicketId(), entity.getApprovalRequestId(),
            entity.getPolicyDecisionId(), entity.getArchivedAt()
        );
    }
}
