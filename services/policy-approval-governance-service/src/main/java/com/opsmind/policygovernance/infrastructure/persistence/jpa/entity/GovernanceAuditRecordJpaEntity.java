package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(schema = "governance", name = "governance_audit_records")
public class GovernanceAuditRecordJpaEntity {

    @Id
    @Column(name = "audit_record_id")
    private String auditRecordId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "source_domain")
    private String sourceDomain;

    @Column(name = "source_request_id")
    private String sourceRequestId;

    @Column(name = "policy_id")
    private String policyId;

    @Column(name = "policy_version")
    private String policyVersion;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "causation_id")
    private String causationId;

    @Column(name = "integrity_hash", nullable = false)
    private String integrityHash;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected GovernanceAuditRecordJpaEntity() {
    }

    public GovernanceAuditRecordJpaEntity(
        String auditRecordId, String action, String actorId, String sourceDomain, String sourceRequestId,
        String policyId, String policyVersion, String reason, String correlationId, String causationId,
        String integrityHash, Instant recordedAt
    ) {
        this.auditRecordId = auditRecordId;
        this.action = action;
        this.actorId = actorId;
        this.sourceDomain = sourceDomain;
        this.sourceRequestId = sourceRequestId;
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.reason = reason;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.integrityHash = integrityHash;
        this.recordedAt = recordedAt;
    }

    public String getAuditRecordId() {
        return auditRecordId;
    }

    public String getAction() {
        return action;
    }

    public String getActorId() {
        return actorId;
    }

    public String getSourceDomain() {
        return sourceDomain;
    }

    public String getSourceRequestId() {
        return sourceRequestId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public String getIntegrityHash() {
        return integrityHash;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
