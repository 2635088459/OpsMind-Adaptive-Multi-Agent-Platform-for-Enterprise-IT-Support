package com.opsmind.identity.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** Implements {@link Persistable} — see {@code UserIdentityJpaEntity}'s own javadoc for why. */
@Entity
@Table(schema = "identity", name = "break_glass_grants")
public class BreakGlassGrantJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "break_glass_grant_id")
    private String breakGlassGrantId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id")
    private String scopeId;

    @Column(name = "approval_reference", nullable = false)
    private String approvalReference;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "granted_by", nullable = false)
    private String grantedBy;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_by")
    private String revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity;

    protected BreakGlassGrantJpaEntity() {
    }

    public BreakGlassGrantJpaEntity(
        String breakGlassGrantId, String tenantId, String issuer, String subject, String scopeType, String scopeId,
        String approvalReference, String reason, String grantedBy, String status, Instant grantedAt, Instant expiresAt,
        String revokedBy, Instant revokedAt, String revocationReason, String correlationId, Instant createdAt, Instant updatedAt,
        long version, boolean newEntity
    ) {
        this.newEntity = newEntity;
        this.breakGlassGrantId = breakGlassGrantId;
        this.tenantId = tenantId;
        this.issuer = issuer;
        this.subject = subject;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.approvalReference = approvalReference;
        this.reason = reason;
        this.grantedBy = grantedBy;
        this.status = status;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getBreakGlassGrantId() {
        return breakGlassGrantId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public String getApprovalReference() {
        return approvalReference;
    }

    public String getReason() {
        return reason;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public String getStatus() {
        return status;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String getId() {
        return breakGlassGrantId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
