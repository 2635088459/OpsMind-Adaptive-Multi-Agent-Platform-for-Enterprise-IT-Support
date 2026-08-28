package com.opsmind.identity.domain.breakglass;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;

import java.time.Instant;
import java.util.Objects;

/**
 * A bounded-time, bounded-scope emergency elevated-access grant
 * (SPEC-UA-019, Break Glass And Account Recovery; 04-use-cases §Break-glass:
 * "Strong authentication + dual/06 approval + bounded time/scope"; 11-security:
 * "Break-glass requires strong authentication, domain-06 approval/dual
 * control, bounded scope/time, and non-disableable audit").
 *
 * <p>{@code approvalReference} is domain 06's own already-decided approval
 * fact, asserted by the trusted caller — this domain has no cross-domain
 * knowledge letting it independently validate or re-derive that decision
 * (02-business-invariants #8: "Domain 01 decides identity-level access and
 * domain 06 decides risk, approval, and business governance"), so it is
 * only ever stored and audited, never interpreted. {@code expiresAt} is
 * mandatory and always in the future relative to {@code grantedAt} — unlike
 * every other aggregate's optional {@code validUntil}, break-glass access
 * can never be unbounded by construction.
 */
public final class BreakGlassGrant {

    private final String breakGlassGrantId;
    private final TenantId tenantId;
    private final ExternalSubject externalSubject;
    private final ResourceScope scope;
    private final String approvalReference;
    private final String reason;
    private final String grantedBy;
    private final BreakGlassStatus status;
    private final Instant grantedAt;
    private final Instant expiresAt;
    private final String revokedBy;
    private final Instant revokedAt;
    private final String revocationReason;
    private final String correlationId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private BreakGlassGrant(
        String breakGlassGrantId, TenantId tenantId, ExternalSubject externalSubject, ResourceScope scope, String approvalReference,
        String reason, String grantedBy, BreakGlassStatus status, Instant grantedAt, Instant expiresAt, String revokedBy,
        Instant revokedAt, String revocationReason, String correlationId, Instant createdAt, Instant updatedAt, long version
    ) {
        this.breakGlassGrantId = Objects.requireNonNull(breakGlassGrantId, "breakGlassGrantId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalSubject = Objects.requireNonNull(externalSubject, "externalSubject");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.approvalReference = requireNonBlank(approvalReference, "approvalReference");
        this.reason = requireNonBlank(reason, "reason");
        this.grantedBy = requireNonBlank(grantedBy, "grantedBy");
        this.status = Objects.requireNonNull(status, "status");
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("expiresAt must be after grantedAt — break-glass access can never be unbounded");
        }
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        this.correlationId = requireNonBlank(correlationId, "correlationId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static BreakGlassGrant activate(
        String breakGlassGrantId, TenantId tenantId, ExternalSubject externalSubject, ResourceScope scope, String approvalReference,
        String reason, String grantedBy, Instant now, Instant expiresAt, String correlationId
    ) {
        return new BreakGlassGrant(
            breakGlassGrantId, tenantId, externalSubject, scope, approvalReference, reason, grantedBy, BreakGlassStatus.ACTIVE,
            now, expiresAt, null, null, null, correlationId, now, now, 0L
        );
    }

    /** Rehydrates a previously-persisted grant. */
    public static BreakGlassGrant reconstruct(
        String breakGlassGrantId, TenantId tenantId, ExternalSubject externalSubject, ResourceScope scope, String approvalReference,
        String reason, String grantedBy, BreakGlassStatus status, Instant grantedAt, Instant expiresAt, String revokedBy,
        Instant revokedAt, String revocationReason, String correlationId, Instant createdAt, Instant updatedAt, long version
    ) {
        return new BreakGlassGrant(
            breakGlassGrantId, tenantId, externalSubject, scope, approvalReference, reason, grantedBy, status, grantedAt, expiresAt,
            revokedBy, revokedAt, revocationReason, correlationId, createdAt, updatedAt, version
        );
    }

    /** System-driven once {@code now} reaches {@link #expiresAt} — admin/scheduler-triggered reconciliation, mirroring every other time-bounded aggregate in this domain. */
    public BreakGlassGrant expire(Instant now) {
        if (status != BreakGlassStatus.ACTIVE) {
            throw new IllegalBreakGlassTransitionException(status, BreakGlassStatus.EXPIRED);
        }
        return new BreakGlassGrant(
            breakGlassGrantId, tenantId, externalSubject, scope, approvalReference, reason, grantedBy, BreakGlassStatus.EXPIRED,
            grantedAt, expiresAt, revokedBy, revokedAt, revocationReason, correlationId, createdAt, now, version + 1
        );
    }

    /** Admin-triggered early termination — misuse detection need not wait for the bounded window to elapse on its own. */
    public BreakGlassGrant revoke(String revokedBy, String reason, Instant now) {
        if (status != BreakGlassStatus.ACTIVE) {
            throw new IllegalBreakGlassTransitionException(status, BreakGlassStatus.REVOKED);
        }
        return new BreakGlassGrant(
            breakGlassGrantId, tenantId, externalSubject, scope, approvalReference, this.reason, grantedBy, BreakGlassStatus.REVOKED,
            grantedAt, expiresAt, Objects.requireNonNull(revokedBy, "revokedBy"), now, reason, correlationId, createdAt, now, version + 1
        );
    }

    public boolean isValid(Instant now) {
        return status == BreakGlassStatus.ACTIVE && now.isBefore(expiresAt);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public String breakGlassGrantId() {
        return breakGlassGrantId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public ExternalSubject externalSubject() {
        return externalSubject;
    }

    public ResourceScope scope() {
        return scope;
    }

    public String approvalReference() {
        return approvalReference;
    }

    public String reason() {
        return reason;
    }

    public String grantedBy() {
        return grantedBy;
    }

    public BreakGlassStatus status() {
        return status;
    }

    public Instant grantedAt() {
        return grantedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
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

    public String correlationId() {
        return correlationId;
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
