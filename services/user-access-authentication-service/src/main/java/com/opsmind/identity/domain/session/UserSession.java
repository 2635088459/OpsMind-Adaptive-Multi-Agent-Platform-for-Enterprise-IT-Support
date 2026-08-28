package com.opsmind.identity.domain.session;

import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;

import java.time.Instant;
import java.util.Objects;

/**
 * Session/revocation metadata (01-domain-model §UserSession). Only hashes
 * and metadata are stored — never access, refresh, or ID tokens
 * (INV-UA-001). This is the structural session shape only; the real OIDC
 * Authorization Code + PKCE login callback that becomes this class's
 * primary caller is SPEC-UA-005's job, and refresh/logout/revocation-list
 * reconciliation are SPEC-UA-009's and SPEC-UA-033's.
 */
public final class UserSession {

    private final String userSessionId;
    private final TenantId tenantId;
    private final ExternalSubject externalSubject;
    private final String idpSessionIdHash;
    private final String tokenIdHash;
    private final String clientId;
    private final AuthenticationAssurance assurance;
    private final String deviceIdHash;
    private final SessionStatus status;
    private final Instant startedAt;
    private final Instant lastSeenAt;
    private final Instant expiresAt;
    private final String revokedBy;
    private final Instant revokedAt;
    private final String revocationReason;
    private final Instant endSessionNotifiedAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private UserSession(
        String userSessionId, TenantId tenantId, ExternalSubject externalSubject, String idpSessionIdHash, String tokenIdHash,
        String clientId, AuthenticationAssurance assurance, String deviceIdHash, SessionStatus status, Instant startedAt,
        Instant lastSeenAt, Instant expiresAt, String revokedBy, Instant revokedAt, String revocationReason, Instant endSessionNotifiedAt,
        Instant createdAt, Instant updatedAt, long version
    ) {
        this.userSessionId = Objects.requireNonNull(userSessionId, "userSessionId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalSubject = Objects.requireNonNull(externalSubject, "externalSubject");
        this.idpSessionIdHash = idpSessionIdHash;
        this.tokenIdHash = tokenIdHash;
        this.clientId = clientId;
        this.assurance = Objects.requireNonNull(assurance, "assurance");
        this.deviceIdHash = deviceIdHash;
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("expiresAt must be after startedAt");
        }
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        this.endSessionNotifiedAt = endSessionNotifiedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static UserSession start(
        String userSessionId, TenantId tenantId, ExternalSubject externalSubject, String idpSessionIdHash, String tokenIdHash,
        String clientId, AuthenticationAssurance assurance, String deviceIdHash, Instant now, Instant expiresAt
    ) {
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            SessionStatus.ACTIVE, now, now, expiresAt, null, null, null, null, now, now, 0L
        );
    }

    /** Rehydrates a previously-persisted session. Used only by a future persistence mapper (SPEC-UA-002). */
    public static UserSession reconstruct(
        String userSessionId, TenantId tenantId, ExternalSubject externalSubject, String idpSessionIdHash, String tokenIdHash,
        String clientId, AuthenticationAssurance assurance, String deviceIdHash, SessionStatus status, Instant startedAt,
        Instant lastSeenAt, Instant expiresAt, String revokedBy, Instant revokedAt, String revocationReason, Instant endSessionNotifiedAt,
        Instant createdAt, Instant updatedAt, long version
    ) {
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            status, startedAt, lastSeenAt, expiresAt, revokedBy, revokedAt, revocationReason, endSessionNotifiedAt, createdAt, updatedAt, version
        );
    }

    public boolean isValid(Instant now) {
        return status == SessionStatus.ACTIVE && now.isBefore(expiresAt);
    }

    /** 09-concurrency-and-idempotency: repeated revoke calls are idempotent at the application-service layer, not here — this method itself is strict. */
    public UserSession revoke(String revokedBy, String reason, Instant now) {
        if (status != SessionStatus.ACTIVE) {
            throw new IllegalUserSessionTransitionException(status, SessionStatus.REVOKED);
        }
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            SessionStatus.REVOKED, startedAt, lastSeenAt, expiresAt, Objects.requireNonNull(revokedBy, "revokedBy"), now, reason, null, createdAt, now, version + 1
        );
    }

    public UserSession expire(Instant now) {
        if (status != SessionStatus.ACTIVE) {
            throw new IllegalUserSessionTransitionException(status, SessionStatus.EXPIRED);
        }
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            SessionStatus.EXPIRED, startedAt, lastSeenAt, expiresAt, revokedBy, revokedAt, "expired", endSessionNotifiedAt, createdAt, now, version + 1
        );
    }

    public UserSession markCompromised(String reason, Instant now) {
        if (status != SessionStatus.ACTIVE) {
            throw new IllegalUserSessionTransitionException(status, SessionStatus.COMPROMISED);
        }
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            SessionStatus.COMPROMISED, startedAt, lastSeenAt, expiresAt, revokedBy, now, reason, endSessionNotifiedAt, createdAt, now, version + 1
        );
    }

    public UserSession terminate(Instant now) {
        if (status != SessionStatus.ACTIVE) {
            throw new IllegalUserSessionTransitionException(status, SessionStatus.TERMINATED);
        }
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            SessionStatus.TERMINATED, startedAt, lastSeenAt, expiresAt, revokedBy, revokedAt, "normal termination", endSessionNotifiedAt, createdAt, now, version + 1
        );
    }

    /** Token refresh may update controlled metadata (10-failure-handling) — never undoes revocation, since it requires {@code ACTIVE}. */
    public UserSession touch(Instant now) {
        if (status != SessionStatus.ACTIVE) {
            throw new IllegalUserSessionTransitionException(status, status);
        }
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            status, startedAt, now, expiresAt, revokedBy, revokedAt, revocationReason, endSessionNotifiedAt, createdAt, now, version + 1
        );
    }

    /**
     * SPEC-UA-009 (04-use-cases §Logout/revocation: "request IdP end-session/revocation";
     * 10-failure-handling: "Keep local revocation and retry if IdP is
     * unavailable"). Legal only once already {@code REVOKED} — records that
     * the best-effort IdP notification succeeded, so reconciliation stops
     * retrying it. Metadata-only: does not itself change {@code status}.
     */
    public UserSession markEndSessionNotified(Instant now) {
        if (status != SessionStatus.REVOKED) {
            throw new IllegalUserSessionTransitionException(status, status);
        }
        return new UserSession(
            userSessionId, tenantId, externalSubject, idpSessionIdHash, tokenIdHash, clientId, assurance, deviceIdHash,
            status, startedAt, lastSeenAt, expiresAt, revokedBy, revokedAt, revocationReason, now, createdAt, now, version + 1
        );
    }

    public String userSessionId() {
        return userSessionId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public ExternalSubject externalSubject() {
        return externalSubject;
    }

    public String idpSessionIdHash() {
        return idpSessionIdHash;
    }

    public String tokenIdHash() {
        return tokenIdHash;
    }

    public String clientId() {
        return clientId;
    }

    public AuthenticationAssurance assurance() {
        return assurance;
    }

    public String deviceIdHash() {
        return deviceIdHash;
    }

    public SessionStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
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

    public Instant endSessionNotifiedAt() {
        return endSessionNotifiedAt;
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
