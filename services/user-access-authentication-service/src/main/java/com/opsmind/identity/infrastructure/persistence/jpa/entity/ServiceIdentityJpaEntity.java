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
@Table(schema = "identity", name = "service_identities")
public class ServiceIdentityJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "service_identity_id")
    private String serviceIdentityId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_audiences", nullable = false, columnDefinition = "jsonb")
    private String allowedAudiencesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_scopes", nullable = false, columnDefinition = "jsonb")
    private String allowedScopesJson;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity;

    protected ServiceIdentityJpaEntity() {
    }

    public ServiceIdentityJpaEntity(
        String serviceIdentityId, String tenantId, String issuer, String subject, String clientId, String serviceName,
        String allowedAudiencesJson, String allowedScopesJson, String status, Instant validFrom, Instant validUntil,
        Instant lastSeenAt, Instant disabledAt, Instant createdAt, Instant updatedAt, long version, boolean newEntity
    ) {
        this.newEntity = newEntity;
        this.serviceIdentityId = serviceIdentityId;
        this.tenantId = tenantId;
        this.issuer = issuer;
        this.subject = subject;
        this.clientId = clientId;
        this.serviceName = serviceName;
        this.allowedAudiencesJson = allowedAudiencesJson;
        this.allowedScopesJson = allowedScopesJson;
        this.status = status;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.lastSeenAt = lastSeenAt;
        this.disabledAt = disabledAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getServiceIdentityId() {
        return serviceIdentityId;
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

    public String getClientId() {
        return clientId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getAllowedAudiencesJson() {
        return allowedAudiencesJson;
    }

    public String getAllowedScopesJson() {
        return allowedScopesJson;
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

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
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
        return serviceIdentityId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
