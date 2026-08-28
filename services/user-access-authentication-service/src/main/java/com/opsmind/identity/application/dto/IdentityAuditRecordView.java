package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;

import java.time.Instant;

public record IdentityAuditRecordView(
    String auditId,
    String tenantId,
    IdentityAuditAction action,
    String actorRef,
    String subjectRef,
    String resourceRef,
    AuditOutcome outcome,
    String reasonCode,
    String correlationId,
    Instant occurredAt,
    String previousHash,
    String recordHash
) {
    public static IdentityAuditRecordView from(IdentityAuditRecord r) {
        return new IdentityAuditRecordView(
            r.auditId(), r.tenantId().value(), r.action(), r.actorRef(), r.subjectRef(), r.resourceRef(),
            r.outcome(), r.reasonCode(), r.correlationId().value(), r.occurredAt(), r.previousHash(), r.recordHash()
        );
    }
}
