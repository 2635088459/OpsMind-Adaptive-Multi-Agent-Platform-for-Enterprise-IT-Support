package com.opsmind.identity.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** Implements {@link Persistable} — see {@code UserIdentityJpaEntity}'s own javadoc for why. */
@Entity
@Table(schema = "identity", name = "role_assignments")
public class RoleAssignmentJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "role_assignment_id")
    private String roleAssignmentId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "user_identity_id", nullable = false)
    private String userIdentityId;

    @Column(name = "role_code", nullable = false)
    private String roleCode;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id")
    private String scopeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", nullable = false, columnDefinition = "jsonb")
    private String permissionsJson;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "granted_by", nullable = false)
    private String grantedBy;

    @Column(name = "grant_reason")
    private String grantReason;

    @Column(name = "revoked_by")
    private String revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity;

    protected RoleAssignmentJpaEntity() {
    }

    public RoleAssignmentJpaEntity(
        String roleAssignmentId, String tenantId, String userIdentityId, String roleCode, String scopeType, String scopeId,
        String permissionsJson, String status, Instant validFrom, Instant validUntil, String grantedBy, String grantReason,
        String revokedBy, Instant revokedAt, String revocationReason, Instant createdAt, Instant updatedAt, long version, boolean newEntity
    ) {
        this.newEntity = newEntity;
        this.roleAssignmentId = roleAssignmentId;
        this.tenantId = tenantId;
        this.userIdentityId = userIdentityId;
        this.roleCode = roleCode;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.permissionsJson = permissionsJson;
        this.status = status;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.grantedBy = grantedBy;
        this.grantReason = grantReason;
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getRoleAssignmentId() {
        return roleAssignmentId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserIdentityId() {
        return userIdentityId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public String getPermissionsJson() {
        return permissionsJson;
    }

    public String getStatus() {
        return status;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public String getGrantReason() {
        return grantReason;
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
        return roleAssignmentId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
