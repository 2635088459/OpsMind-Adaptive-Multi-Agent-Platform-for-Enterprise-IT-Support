package com.opsmind.identity.domain.role;

import com.opsmind.identity.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A grant of one {@link RoleCode} to one {@code UserIdentity} at a {@link
 * ResourceScope} (01-domain-model §RoleAssignment). {@link #activate}
 * starts a grant at {@code validFrom} rather than immediately — SPEC-UA-001
 * itself only builds the direct-activate path ({@link #grantActive});
 * scheduled/delegated grants are SPEC-UA-012's job (Role Assignment
 * Lifecycle).
 */
public final class RoleAssignment {

    private final String roleAssignmentId;
    private final TenantId tenantId;
    private final String userIdentityId;
    private final RoleCode roleCode;
    private final ResourceScope scope;
    private final List<String> permissions;
    private final RoleAssignmentStatus status;
    private final Instant validFrom;
    private final Instant validUntil;
    private final String grantedBy;
    private final String grantReason;
    private final String revokedBy;
    private final Instant revokedAt;
    private final String revocationReason;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private RoleAssignment(
        String roleAssignmentId, TenantId tenantId, String userIdentityId, RoleCode roleCode, ResourceScope scope,
        List<String> permissions, RoleAssignmentStatus status, Instant validFrom, Instant validUntil, String grantedBy,
        String grantReason, String revokedBy, Instant revokedAt, String revocationReason, Instant createdAt, Instant updatedAt, long version
    ) {
        this.roleAssignmentId = Objects.requireNonNull(roleAssignmentId, "roleAssignmentId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.userIdentityId = Objects.requireNonNull(userIdentityId, "userIdentityId");
        this.roleCode = Objects.requireNonNull(roleCode, "roleCode");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.permissions = List.copyOf(permissions == null ? List.of() : permissions);
        this.status = Objects.requireNonNull(status, "status");
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.grantedBy = Objects.requireNonNull(grantedBy, "grantedBy");
        this.grantReason = grantReason;
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** SPEC-UA-001's own direct grant path: skips {@code PENDING} and starts {@code ACTIVE} immediately. */
    public static RoleAssignment grantActive(
        String roleAssignmentId, TenantId tenantId, String userIdentityId, RoleCode roleCode, ResourceScope scope,
        List<String> permissions, Instant validUntil, String grantedBy, String grantReason, Instant now
    ) {
        return new RoleAssignment(
            roleAssignmentId, tenantId, userIdentityId, roleCode, scope, permissions, RoleAssignmentStatus.ACTIVE,
            now, validUntil, grantedBy, grantReason, null, null, null, now, now, 0L
        );
    }

    /** Rehydrates a previously-persisted assignment. Used only by a future persistence mapper (SPEC-UA-002). */
    public static RoleAssignment reconstruct(
        String roleAssignmentId, TenantId tenantId, String userIdentityId, RoleCode roleCode, ResourceScope scope,
        List<String> permissions, RoleAssignmentStatus status, Instant validFrom, Instant validUntil, String grantedBy,
        String grantReason, String revokedBy, Instant revokedAt, String revocationReason, Instant createdAt, Instant updatedAt, long version
    ) {
        return new RoleAssignment(
            roleAssignmentId, tenantId, userIdentityId, roleCode, scope, permissions, status, validFrom, validUntil,
            grantedBy, grantReason, revokedBy, revokedAt, revocationReason, createdAt, updatedAt, version
        );
    }

    public RoleAssignment revoke(String revokedBy, String reason, Instant now) {
        if (status != RoleAssignmentStatus.ACTIVE) {
            throw new IllegalRoleAssignmentTransitionException(status, RoleAssignmentStatus.REVOKED);
        }
        return new RoleAssignment(
            roleAssignmentId, tenantId, userIdentityId, roleCode, scope, permissions, RoleAssignmentStatus.REVOKED,
            validFrom, validUntil, grantedBy, grantReason, Objects.requireNonNull(revokedBy, "revokedBy"), now, reason, createdAt, now, version + 1
        );
    }

    /** System-driven once {@code now} passes {@link #validUntil}. */
    public RoleAssignment expire(Instant now) {
        if (status != RoleAssignmentStatus.ACTIVE) {
            throw new IllegalRoleAssignmentTransitionException(status, RoleAssignmentStatus.EXPIRED);
        }
        return new RoleAssignment(
            roleAssignmentId, tenantId, userIdentityId, roleCode, scope, permissions, RoleAssignmentStatus.EXPIRED,
            validFrom, validUntil, grantedBy, grantReason, revokedBy, revokedAt, revocationReason, createdAt, now, version + 1
        );
    }

    public boolean isActive() {
        return status == RoleAssignmentStatus.ACTIVE;
    }

    public boolean isActive(Instant now) {
        return isActive() && !now.isBefore(validFrom) && (validUntil == null || now.isBefore(validUntil));
    }

    /** {@code true} if this assignment is currently active for exactly {@code roleCode} at exactly {@code scope}. */
    public boolean matches(RoleCode roleCode, ResourceScope scope, Instant now) {
        return isActive(now) && this.roleCode == roleCode && this.scope.equals(scope);
    }

    public String roleAssignmentId() {
        return roleAssignmentId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String userIdentityId() {
        return userIdentityId;
    }

    public RoleCode roleCode() {
        return roleCode;
    }

    public ResourceScope scope() {
        return scope;
    }

    public List<String> permissions() {
        return permissions;
    }

    public RoleAssignmentStatus status() {
        return status;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validUntil() {
        return validUntil;
    }

    public String grantedBy() {
        return grantedBy;
    }

    public String grantReason() {
        return grantReason;
    }

    public String revokedBy() {
        return revokedBy;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public String revocationReason() {
        return revocationReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
