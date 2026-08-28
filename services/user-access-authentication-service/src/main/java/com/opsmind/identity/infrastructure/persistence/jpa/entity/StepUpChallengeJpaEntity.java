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
@Table(schema = "identity", name = "step_up_challenges")
public class StepUpChallengeJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "step_up_challenge_id")
    private String stepUpChallengeId;

    @Column(name = "challenge_key", nullable = false)
    private String challengeKey;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "user_session_id", nullable = false)
    private String userSessionId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "required_assurance_level")
    private String requiredAssuranceLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_methods", nullable = false, columnDefinition = "jsonb")
    private String requiredMethodsJson;

    @Column(name = "nonce_hash")
    private String nonceHash;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "proof_id_hash")
    private String proofIdHash;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity;

    protected StepUpChallengeJpaEntity() {
    }

    public StepUpChallengeJpaEntity(
        String stepUpChallengeId, String challengeKey, String tenantId, String issuer, String subject, String userSessionId,
        String action, String resourceType, String resourceId, String requiredAssuranceLevel, String requiredMethodsJson,
        String nonceHash, String status, int attemptCount, int maxAttempts, Instant createdAt, Instant expiresAt,
        Instant verifiedAt, String proofIdHash, Instant consumedAt, String correlationId, long version, boolean newEntity
    ) {
        this.newEntity = newEntity;
        this.stepUpChallengeId = stepUpChallengeId;
        this.challengeKey = challengeKey;
        this.tenantId = tenantId;
        this.issuer = issuer;
        this.subject = subject;
        this.userSessionId = userSessionId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.requiredAssuranceLevel = requiredAssuranceLevel;
        this.requiredMethodsJson = requiredMethodsJson;
        this.nonceHash = nonceHash;
        this.status = status;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.verifiedAt = verifiedAt;
        this.proofIdHash = proofIdHash;
        this.consumedAt = consumedAt;
        this.correlationId = correlationId;
        this.version = version;
    }

    public String getStepUpChallengeId() {
        return stepUpChallengeId;
    }

    public String getChallengeKey() {
        return challengeKey;
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

    public String getUserSessionId() {
        return userSessionId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getRequiredAssuranceLevel() {
        return requiredAssuranceLevel;
    }

    public String getRequiredMethodsJson() {
        return requiredMethodsJson;
    }

    public String getNonceHash() {
        return nonceHash;
    }

    public String getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getProofIdHash() {
        return proofIdHash;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String getId() {
        return stepUpChallengeId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
