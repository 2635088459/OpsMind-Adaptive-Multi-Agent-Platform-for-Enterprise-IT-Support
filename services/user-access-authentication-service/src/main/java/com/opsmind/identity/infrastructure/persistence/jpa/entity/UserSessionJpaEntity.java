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
@Table(schema = "identity", name = "user_sessions")
public class UserSessionJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "user_session_id")
    private String userSessionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "idp_session_id_hash")
    private String idpSessionIdHash;

    @Column(name = "token_id_hash")
    private String tokenIdHash;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "acr", nullable = false)
    private String acr;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "amr", nullable = false, columnDefinition = "jsonb")
    private String amrJson;

    @Column(name = "auth_time", nullable = false)
    private Instant authTime;

    @Column(name = "device_id_hash")
    private String deviceIdHash;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_by")
    private String revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Column(name = "end_session_notified_at")
    private Instant endSessionNotifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity;

    protected UserSessionJpaEntity() {
    }

    public UserSessionJpaEntity(
        String userSessionId, String tenantId, String issuer, String subject, String idpSessionIdHash, String tokenIdHash,
        String clientId, String acr, String amrJson, Instant authTime, String deviceIdHash, String status, Instant startedAt,
        Instant lastSeenAt, Instant expiresAt, String revokedBy, Instant revokedAt, String revocationReason, Instant endSessionNotifiedAt,
        Instant createdAt, Instant updatedAt, long version, boolean newEntity
    ) {
        this.newEntity = newEntity;
        this.endSessionNotifiedAt = endSessionNotifiedAt;
        this.userSessionId = userSessionId;
        this.tenantId = tenantId;
        this.issuer = issuer;
        this.subject = subject;
        this.idpSessionIdHash = idpSessionIdHash;
        this.tokenIdHash = tokenIdHash;
        this.clientId = clientId;
        this.acr = acr;
        this.amrJson = amrJson;
        this.authTime = authTime;
        this.deviceIdHash = deviceIdHash;
        this.status = status;
        this.startedAt = startedAt;
        this.lastSeenAt = lastSeenAt;
        this.expiresAt = expiresAt;
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getUserSessionId() {
        return userSessionId;
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

    public String getIdpSessionIdHash() {
        return idpSessionIdHash;
    }

    public String getTokenIdHash() {
        return tokenIdHash;
    }

    public String getClientId() {
        return clientId;
    }

    public String getAcr() {
        return acr;
    }

    public String getAmrJson() {
        return amrJson;
    }

    public Instant getAuthTime() {
        return authTime;
    }

    public String getDeviceIdHash() {
        return deviceIdHash;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
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

    public Instant getEndSessionNotifiedAt() {
        return endSessionNotifiedAt;
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
        return userSessionId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
