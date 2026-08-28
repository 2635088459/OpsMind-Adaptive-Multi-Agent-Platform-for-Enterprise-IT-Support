package com.opsmind.identity.infrastructure.persistence.mapper;

import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.IdentityAuditRecordJpaEntity;

public final class IdentityAuditRecordMapper {

    private IdentityAuditRecordMapper() {
    }

    public static IdentityAuditRecordJpaEntity toEntity(IdentityAuditRecord record) {
        return new IdentityAuditRecordJpaEntity(
            record.auditId(), record.tenantId().value(), record.action().name(), record.actorRef(), record.subjectRef(),
            record.resourceRef(), record.outcome().name(), record.reasonCode(), record.correlationId().value(), record.occurredAt(),
            record.previousHash(), record.recordHash()
        );
    }

    public static IdentityAuditRecord toDomain(IdentityAuditRecordJpaEntity entity) {
        return IdentityAuditRecord.reconstruct(
            entity.getAuditId(), new TenantId(entity.getTenantId()), IdentityAuditAction.valueOf(entity.getAction()), entity.getActorRef(),
            entity.getSubjectRef(), entity.getResourceRef(), AuditOutcome.valueOf(entity.getOutcome()), entity.getReasonCode(),
            new CorrelationId(entity.getCorrelationId()), entity.getOccurredAt(), entity.getPreviousHash(), entity.getRecordHash()
        );
    }
}
