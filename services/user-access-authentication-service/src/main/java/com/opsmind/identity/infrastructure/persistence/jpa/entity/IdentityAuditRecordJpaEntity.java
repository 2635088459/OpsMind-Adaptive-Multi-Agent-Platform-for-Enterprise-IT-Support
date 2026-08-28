package com.opsmind.identity.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(schema = "identity", name = "identity_audit_records")
public class IdentityAuditRecordJpaEntity {

    @Id
    @Column(name = "audit_id")
    private String auditId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "actor_ref")
    private String actorRef;

    @Column(name = "subject_ref")
    private String subjectRef;

    @Column(name = "resource_ref")
    private String resourceRef;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "previous_hash")
    private String previousHash;

    @Column(name = "record_hash")
    private String recordHash;

    protected IdentityAuditRecordJpaEntity() {
    }

    public IdentityAuditRecordJpaEntity(
        String auditId, String tenantId, String action, String actorRef, String subjectRef, String resourceRef,
        String outcome, String reasonCode, String correlationId, Instant occurredAt, String previousHash, String recordHash
    ) {
        this.auditId = auditId;
        this.tenantId = tenantId;
        this.action = action;
        this.actorRef = actorRef;
        this.subjectRef = subjectRef;
        this.resourceRef = resourceRef;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
        this.previousHash = previousHash;
        this.recordHash = recordHash;
    }

    public String getAuditId() {
        return auditId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAction() {
        return action;
    }

    public String getActorRef() {
        return actorRef;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public String getResourceRef() {
        return resourceRef;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getRecordHash() {
        return recordHash;
    }
}
