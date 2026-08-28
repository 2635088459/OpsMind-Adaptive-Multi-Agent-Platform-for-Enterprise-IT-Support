package com.opsmind.identity.domain.user;

import com.opsmind.identity.domain.shared.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * The trusted OpsMind user mapping (01-domain-model §UserIdentity;
 * 02-business-invariants: "`(tenantId, issuer, subject)` is the stable user
 * identity. Username, email, display name ... grant no authority").
 * {@code externalSubject} is immutable for the aggregate's lifetime.
 *
 * <p>Full claims normalization (SPEC-UA-007) and provisioning/linking
 * workflows across multiple IdP identities (SPEC-UA-008) build on this
 * shape; {@link #sync} here is only the minimal out-of-order-safe upsert
 * 10-failure-handling names ("upstream version/time prevents stale
 * overwrite").
 */
public final class UserIdentity {

    private final String userIdentityId;
    private final TenantId tenantId;
    private final ExternalSubject externalSubject;
    private final String username;
    private final String displayName;
    private final String email;
    private final IdentityType identityType;
    private final UserStatus status;
    private final long profileVersion;
    private final Instant linkedAt;
    private final Instant lastSyncedAt;
    private final Instant disabledAt;
    private final Instant deprovisionedAt;
    private final Instant piiRedactedAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private UserIdentity(
        String userIdentityId, TenantId tenantId, ExternalSubject externalSubject, String username, String displayName,
        String email, IdentityType identityType, UserStatus status, long profileVersion, Instant linkedAt,
        Instant lastSyncedAt, Instant disabledAt, Instant deprovisionedAt, Instant piiRedactedAt, Instant createdAt, Instant updatedAt, long version
    ) {
        this.userIdentityId = Objects.requireNonNull(userIdentityId, "userIdentityId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalSubject = Objects.requireNonNull(externalSubject, "externalSubject");
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.identityType = Objects.requireNonNull(identityType, "identityType");
        this.status = Objects.requireNonNull(status, "status");
        this.profileVersion = profileVersion;
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt");
        this.lastSyncedAt = lastSyncedAt;
        this.disabledAt = disabledAt;
        this.deprovisionedAt = deprovisionedAt;
        this.piiRedactedAt = piiRedactedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static UserIdentity link(
        String userIdentityId, TenantId tenantId, ExternalSubject externalSubject, String username,
        String displayName, String email, IdentityType identityType, Instant now
    ) {
        return new UserIdentity(
            userIdentityId, tenantId, externalSubject, username, displayName, email, identityType,
            UserStatus.ACTIVE, 0L, now, now, null, null, null, now, now, 0L
        );
    }

    /** Rehydrates a previously-persisted identity. Used only by a future persistence mapper (SPEC-UA-002). */
    public static UserIdentity reconstruct(
        String userIdentityId, TenantId tenantId, ExternalSubject externalSubject, String username, String displayName,
        String email, IdentityType identityType, UserStatus status, long profileVersion, Instant linkedAt,
        Instant lastSyncedAt, Instant disabledAt, Instant deprovisionedAt, Instant piiRedactedAt, Instant createdAt, Instant updatedAt, long version
    ) {
        return new UserIdentity(
            userIdentityId, tenantId, externalSubject, username, displayName, email, identityType, status,
            profileVersion, linkedAt, lastSyncedAt, disabledAt, deprovisionedAt, piiRedactedAt, createdAt, updatedAt, version
        );
    }

    public UserIdentity disable(Instant now) {
        if (status != UserStatus.ACTIVE) {
            throw new IllegalUserIdentityTransitionException(status, UserStatus.DISABLED);
        }
        return new UserIdentity(
            userIdentityId, tenantId, externalSubject, username, displayName, email, identityType,
            UserStatus.DISABLED, profileVersion, linkedAt, lastSyncedAt, now, deprovisionedAt, piiRedactedAt, createdAt, now, version + 1
        );
    }

    public UserIdentity enable(Instant now) {
        if (status != UserStatus.DISABLED) {
            throw new IllegalUserIdentityTransitionException(status, UserStatus.ACTIVE);
        }
        return new UserIdentity(
            userIdentityId, tenantId, externalSubject, username, displayName, email, identityType,
            UserStatus.ACTIVE, profileVersion, linkedAt, lastSyncedAt, null, deprovisionedAt, piiRedactedAt, createdAt, now, version + 1
        );
    }

    /** Terminal — 03-state-machine: "Rehire creates a new mapping ...; old authority is never silently restored." */
    public UserIdentity deprovision(Instant now) {
        if (status == UserStatus.DEPROVISIONED) {
            throw new IllegalUserIdentityTransitionException(status, UserStatus.DEPROVISIONED);
        }
        return new UserIdentity(
            userIdentityId, tenantId, externalSubject, username, displayName, email, identityType,
            UserStatus.DEPROVISIONED, profileVersion, linkedAt, lastSyncedAt, disabledAt, now, piiRedactedAt, createdAt, now, version + 1
        );
    }

    /** 10-failure-handling: a sync with a {@code profileVersion} not newer than the current one is a no-op (stale-overwrite protection). */
    public UserIdentity sync(String username, String displayName, String email, long incomingProfileVersion, Instant now) {
        if (incomingProfileVersion <= profileVersion) {
            return this;
        }
        return new UserIdentity(
            userIdentityId, tenantId, externalSubject, username, displayName, email, identityType, status,
            incomingProfileVersion, linkedAt, now, disabledAt, deprovisionedAt, piiRedactedAt, createdAt, now, version + 1
        );
    }

    /**
     * SPEC-UA-031 (07-data-model: "Email/display name may be encrypted and
     * erased by retention"). Nulls the three fields that carry no authority
     * of their own (02-business-invariants already says as much) while
     * preserving the immutable {@code (tenantId, issuer, subject)} identity
     * key and every audit-relevant timestamp. Only meaningful once terminal
     * ({@code DEPROVISIONED}) — a still-linked identity's PII is live data,
     * not retention debt. Idempotent: redacting an already-redacted identity
     * is a no-op, matching {@link #sync}'s own stale-write tolerance.
     */
    public UserIdentity redactPii(Instant now) {
        if (piiRedactedAt != null) {
            return this;
        }
        if (status != UserStatus.DEPROVISIONED) {
            throw new IllegalStateException("cannot redact PII for user identity " + userIdentityId + ": not DEPROVISIONED");
        }
        return new UserIdentity(
            userIdentityId, tenantId, externalSubject, null, null, null, identityType,
            status, profileVersion, linkedAt, lastSyncedAt, disabledAt, deprovisionedAt, now, createdAt, now, version + 1
        );
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public String userIdentityId() {
        return userIdentityId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public ExternalSubject externalSubject() {
        return externalSubject;
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public IdentityType identityType() {
        return identityType;
    }

    public UserStatus status() {
        return status;
    }

    public long profileVersion() {
        return profileVersion;
    }

    public Instant linkedAt() {
        return linkedAt;
    }

    public Instant lastSyncedAt() {
        return lastSyncedAt;
    }

    public Instant disabledAt() {
        return disabledAt;
    }

    public Instant deprovisionedAt() {
        return deprovisionedAt;
    }

    public Instant piiRedactedAt() {
        return piiRedactedAt;
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
