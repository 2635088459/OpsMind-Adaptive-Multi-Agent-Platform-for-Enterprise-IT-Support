package com.opsmind.identity.domain.stepup;

import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A short-lived, single-use step-up proof request (01-domain-model
 * §StepUpChallenge; 02-business-invariants INV-UA-005: "Step-up evidence
 * binds issuer, subject, session, action, resource, assurance, and expiry
 * and is single use"). {@link #consume} is legal only once, from {@code
 * VERIFIED} — the domain-level half of 09-concurrency-and-idempotency's
 * "atomic conditional update ... only one concurrent consumer succeeds"
 * (the actual atomic compare-and-swap is the persistence adapter's job,
 * SPEC-UA-002).
 *
 * <p>No actual proof material (a TOTP code, a WebAuthn assertion) is
 * modeled or checked here — SPEC-UA-017/018 own the real challenge-method
 * catalogue and proof validation against Keycloak MFA.
 */
public final class StepUpChallenge {

    private final String stepUpChallengeId;
    private final String challengeKey;
    private final TenantId tenantId;
    private final ExternalSubject externalSubject;
    private final String userSessionId;
    private final AuthorizationTarget target;
    private final String requiredAssuranceLevel;
    private final List<String> requiredMethods;
    private final String nonceHash;
    private final StepUpStatus status;
    private final int attemptCount;
    private final int maxAttempts;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant verifiedAt;
    private final String proofIdHash;
    private final Instant consumedAt;
    private final String correlationId;
    private final long version;

    private StepUpChallenge(
        String stepUpChallengeId, String challengeKey, TenantId tenantId, ExternalSubject externalSubject, String userSessionId,
        AuthorizationTarget target, String requiredAssuranceLevel, List<String> requiredMethods, String nonceHash,
        StepUpStatus status, int attemptCount, int maxAttempts, Instant createdAt, Instant expiresAt, Instant verifiedAt,
        String proofIdHash, Instant consumedAt, String correlationId, long version
    ) {
        this.stepUpChallengeId = Objects.requireNonNull(stepUpChallengeId, "stepUpChallengeId");
        this.challengeKey = Objects.requireNonNull(challengeKey, "challengeKey");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalSubject = Objects.requireNonNull(externalSubject, "externalSubject");
        this.userSessionId = Objects.requireNonNull(userSessionId, "userSessionId");
        this.target = Objects.requireNonNull(target, "target");
        this.requiredAssuranceLevel = requiredAssuranceLevel;
        this.requiredMethods = List.copyOf(requiredMethods == null ? List.of() : requiredMethods);
        this.nonceHash = nonceHash;
        this.status = Objects.requireNonNull(status, "status");
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        this.verifiedAt = verifiedAt;
        this.proofIdHash = proofIdHash;
        this.consumedAt = consumedAt;
        this.correlationId = requireNonBlank(correlationId, "correlationId");
        this.version = version;
    }

    public static StepUpChallenge request(
        String stepUpChallengeId, String challengeKey, TenantId tenantId, ExternalSubject externalSubject, String userSessionId,
        AuthorizationTarget target, String requiredAssuranceLevel, List<String> requiredMethods, int maxAttempts,
        String correlationId, Instant now, Instant expiresAt
    ) {
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, null, StepUpStatus.REQUESTED, 0, maxAttempts, now, expiresAt, null, null, null, correlationId, 0L
        );
    }

    /** Rehydrates a previously-persisted challenge. Used only by a future persistence mapper (SPEC-UA-002). */
    public static StepUpChallenge reconstruct(
        String stepUpChallengeId, String challengeKey, TenantId tenantId, ExternalSubject externalSubject, String userSessionId,
        AuthorizationTarget target, String requiredAssuranceLevel, List<String> requiredMethods, String nonceHash,
        StepUpStatus status, int attemptCount, int maxAttempts, Instant createdAt, Instant expiresAt, Instant verifiedAt,
        String proofIdHash, Instant consumedAt, String correlationId, long version
    ) {
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, nonceHash, status, attemptCount, maxAttempts, createdAt, expiresAt, verifiedAt, proofIdHash,
            consumedAt, correlationId, version
        );
    }

    public StepUpChallenge dispatch(String nonceHash, Instant now) {
        if (status != StepUpStatus.REQUESTED) {
            throw new IllegalStepUpTransitionException(status, StepUpStatus.PENDING);
        }
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, nonceHash, StepUpStatus.PENDING, attemptCount, maxAttempts, createdAt, expiresAt, verifiedAt,
            proofIdHash, consumedAt, correlationId, version + 1
        );
    }

    public StepUpChallenge verify(String proofIdHash, Instant now) {
        if (status != StepUpStatus.PENDING) {
            throw new IllegalStepUpTransitionException(status, StepUpStatus.VERIFIED);
        }
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStepUpTransitionException(status, StepUpStatus.EXPIRED);
        }
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, nonceHash, StepUpStatus.VERIFIED, attemptCount, maxAttempts, createdAt, expiresAt,
            Objects.requireNonNull(now, "now"), Objects.requireNonNull(proofIdHash, "proofIdHash"), consumedAt, correlationId, version + 1
        );
    }

    /** Single-use: legal only from {@code VERIFIED} (INV-UA-005). */
    public StepUpChallenge consume(Instant now) {
        if (status != StepUpStatus.VERIFIED) {
            throw new IllegalStepUpTransitionException(status, StepUpStatus.CONSUMED);
        }
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, nonceHash, StepUpStatus.CONSUMED, attemptCount, maxAttempts, createdAt, expiresAt, verifiedAt,
            proofIdHash, Objects.requireNonNull(now, "now"), correlationId, version + 1
        );
    }

    /** A failed verification attempt while {@code PENDING}. Transitions to {@code FAILED} once {@link #maxAttempts} is reached. */
    public StepUpChallenge failAttempt(Instant now) {
        if (status != StepUpStatus.PENDING) {
            throw new IllegalStepUpTransitionException(status, StepUpStatus.FAILED);
        }
        int nextAttempt = attemptCount + 1;
        StepUpStatus nextStatus = nextAttempt >= maxAttempts ? StepUpStatus.FAILED : StepUpStatus.PENDING;
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, nonceHash, nextStatus, nextAttempt, maxAttempts, createdAt, expiresAt, verifiedAt,
            proofIdHash, consumedAt, correlationId, version + 1
        );
    }

    public StepUpChallenge expire(Instant now) {
        if (status != StepUpStatus.PENDING) {
            throw new IllegalStepUpTransitionException(status, StepUpStatus.EXPIRED);
        }
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, nonceHash, StepUpStatus.EXPIRED, attemptCount, maxAttempts, createdAt, expiresAt, verifiedAt,
            proofIdHash, consumedAt, correlationId, version + 1
        );
    }

    public StepUpChallenge cancel(Instant now) {
        if (status != StepUpStatus.PENDING) {
            throw new IllegalStepUpTransitionException(status, StepUpStatus.CANCELLED);
        }
        return new StepUpChallenge(
            stepUpChallengeId, challengeKey, tenantId, externalSubject, userSessionId, target, requiredAssuranceLevel,
            requiredMethods, nonceHash, StepUpStatus.CANCELLED, attemptCount, maxAttempts, createdAt, expiresAt, verifiedAt,
            proofIdHash, consumedAt, correlationId, version + 1
        );
    }

    public boolean isVerified() {
        return status == StepUpStatus.VERIFIED;
    }

    public boolean isConsumed() {
        return status == StepUpStatus.CONSUMED;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public String stepUpChallengeId() {
        return stepUpChallengeId;
    }

    public String challengeKey() {
        return challengeKey;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public ExternalSubject externalSubject() {
        return externalSubject;
    }

    public String userSessionId() {
        return userSessionId;
    }

    public AuthorizationTarget target() {
        return target;
    }

    public String requiredAssuranceLevel() {
        return requiredAssuranceLevel;
    }

    public List<String> requiredMethods() {
        return requiredMethods;
    }

    public String nonceHash() {
        return nonceHash;
    }

    public StepUpStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }

    public String proofIdHash() {
        return proofIdHash;
    }

    public Instant consumedAt() {
        return consumedAt;
    }

    public String correlationId() {
        return correlationId;
    }

    public long version() {
        return version;
    }
}
