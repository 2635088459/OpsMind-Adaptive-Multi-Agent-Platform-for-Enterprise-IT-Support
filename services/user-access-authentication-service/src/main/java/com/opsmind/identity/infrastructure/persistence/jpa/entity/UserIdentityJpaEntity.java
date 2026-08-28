package com.opsmind.identity.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Implements {@link Persistable} because this entity's id is always
 * client-assigned (never {@code @GeneratedValue}) and a fresh detached
 * instance is rebuilt on every {@code save()} from the immutable domain
 * object — Spring Data JPA's default "is the id null?" and "is the
 * primitive {@code @Version} zero?" heuristics both misfire here (the
 * latter cannot tell a genuinely new row apart from an update whose
 * pre-transition version happens to be zero), so {@link #isNew()} carries
 * its own explicit signal instead (09-concurrency-and-idempotency: this is
 * what makes the {@code @Version} optimistic check actually protect a real
 * concurrent-update race rather than throwing a false {@code
 * ObjectOptimisticLockingFailureException} on every second save).
 */
@Entity
@Table(schema = "identity", name = "user_identities")
public class UserIdentityJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "user_identity_id")
    private String userIdentityId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "username")
    private String username;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "email")
    private String email;

    @Column(name = "identity_type", nullable = false)
    private String identityType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "profile_version", nullable = false)
    private long profileVersion;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "deprovisioned_at")
    private Instant deprovisionedAt;

    @Column(name = "pii_redacted_at")
    private Instant piiRedactedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity;

    protected UserIdentityJpaEntity() {
    }

    public UserIdentityJpaEntity(
        String userIdentityId, String tenantId, String issuer, String subject, String username, String displayName,
        String email, String identityType, String status, long profileVersion, Instant linkedAt, Instant lastSyncedAt,
        Instant disabledAt, Instant deprovisionedAt, Instant piiRedactedAt, Instant createdAt, Instant updatedAt, long version, boolean newEntity
    ) {
        this.newEntity = newEntity;
        this.userIdentityId = userIdentityId;
        this.tenantId = tenantId;
        this.issuer = issuer;
        this.subject = subject;
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.identityType = identityType;
        this.status = status;
        this.profileVersion = profileVersion;
        this.linkedAt = linkedAt;
        this.lastSyncedAt = lastSyncedAt;
        this.disabledAt = disabledAt;
        this.deprovisionedAt = deprovisionedAt;
        this.piiRedactedAt = piiRedactedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getUserIdentityId() {
        return userIdentityId;
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

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getIdentityType() {
        return identityType;
    }

    public String getStatus() {
        return status;
    }

    public long getProfileVersion() {
        return profileVersion;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public Instant getDeprovisionedAt() {
        return deprovisionedAt;
    }

    public Instant getPiiRedactedAt() {
        return piiRedactedAt;
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
        return userIdentityId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
