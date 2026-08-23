package com.opsmind.policygovernance.domain.policy;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A concrete, versioned rule-set snapshot of a {@link Policy}
 * (01-domain-model §Policy, §PolicyRule). Transitions follow
 * 03-state-machine §Policy State Machine exactly; every transition and every
 * attempt to change rules on a {@code PUBLISHED} version is guarded here so
 * INV-PG-006 ("Policy Versions Must Not Rewrite History") holds regardless
 * of which future spec drives the admin workflow (reviewer/publisher
 * separation of duties is validated by the caller — see
 * {@code application.PolicyAdminService} — not by this type).
 */
public final class PolicyVersion {

    private static final Map<PolicyStatus, Set<PolicyStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(PolicyStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(PolicyStatus.DRAFT, EnumSet.of(PolicyStatus.REVIEWING, PolicyStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(PolicyStatus.REVIEWING, EnumSet.of(PolicyStatus.PUBLISHED, PolicyStatus.REJECTED));
        ALLOWED_TRANSITIONS.put(PolicyStatus.REJECTED, EnumSet.of(PolicyStatus.DRAFT));
        ALLOWED_TRANSITIONS.put(PolicyStatus.PUBLISHED, EnumSet.of(PolicyStatus.DEPRECATED, PolicyStatus.SUPERSEDED));
        ALLOWED_TRANSITIONS.put(PolicyStatus.DEPRECATED, EnumSet.of(PolicyStatus.ARCHIVED));
        ALLOWED_TRANSITIONS.put(PolicyStatus.ARCHIVED, EnumSet.noneOf(PolicyStatus.class));
        ALLOWED_TRANSITIONS.put(PolicyStatus.CANCELLED, EnumSet.noneOf(PolicyStatus.class));
        ALLOWED_TRANSITIONS.put(PolicyStatus.SUPERSEDED, EnumSet.noneOf(PolicyStatus.class));
    }

    private final String policyVersionId;
    private final String policyId;
    private final int versionNumber;
    private final PolicyStatus status;
    private final List<PolicyRule> rules;
    private final Instant effectiveFrom;
    private final Instant effectiveTo;
    private final String createdBy;
    private final String reviewedBy;
    private final String publishedBy;
    private final Instant publishedAt;

    private PolicyVersion(
        String policyVersionId,
        String policyId,
        int versionNumber,
        PolicyStatus status,
        List<PolicyRule> rules,
        Instant effectiveFrom,
        Instant effectiveTo,
        String createdBy,
        String reviewedBy,
        String publishedBy,
        Instant publishedAt
    ) {
        this.policyVersionId = Objects.requireNonNull(policyVersionId, "policyVersionId");
        this.policyId = Objects.requireNonNull(policyId, "policyId");
        this.versionNumber = versionNumber;
        this.status = Objects.requireNonNull(status, "status");
        this.rules = List.copyOf(rules == null ? List.of() : rules);
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.reviewedBy = reviewedBy;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
    }

    public static PolicyVersion draft(
        String policyVersionId, String policyId, int versionNumber, List<PolicyRule> rules, String createdBy
    ) {
        return new PolicyVersion(policyVersionId, policyId, versionNumber, PolicyStatus.DRAFT, rules, null, null, createdBy, null, null, null);
    }

    /**
     * Rehydrates a previously-persisted snapshot exactly as stored — unlike
     * {@link #draft}, this does not assert {@code DRAFT} status or apply any
     * transition guard, since the row already passed those guards when it
     * was written. Used only by {@code infrastructure.persistence.mapper.PolicyVersionMapper}.
     */
    public static PolicyVersion reconstruct(
        String policyVersionId, String policyId, int versionNumber, PolicyStatus status, List<PolicyRule> rules,
        Instant effectiveFrom, Instant effectiveTo, String createdBy, String reviewedBy, String publishedBy, Instant publishedAt
    ) {
        return new PolicyVersion(
            policyVersionId, policyId, versionNumber, status, rules, effectiveFrom, effectiveTo,
            createdBy, reviewedBy, publishedBy, publishedAt
        );
    }

    /**
     * Returns a new snapshot in {@code target} status, or throws if the
     * transition is not allowed from the current one. {@code actorId} is
     * recorded as {@code reviewedBy} on a transition into {@code REVIEWING}
     * and as {@code publishedBy} on a transition into {@code PUBLISHED}
     * (07-data-model §policy_versions); {@code effectiveFrom} and the
     * system-clock publish instant ({@code publishedAt}) are only set on
     * the latter.
     */
    public PolicyVersion transitionTo(PolicyStatus target, String actorId, Instant effectiveFrom, Instant now) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actorId, "actorId");
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new IllegalPolicyTransitionException(status, target);
        }
        String nextReviewedBy = target == PolicyStatus.REVIEWING ? actorId : reviewedBy;
        String nextPublishedBy = target == PolicyStatus.PUBLISHED ? actorId : publishedBy;
        Instant nextPublishedAt = target == PolicyStatus.PUBLISHED ? now : publishedAt;
        Instant nextEffectiveFrom = target == PolicyStatus.PUBLISHED ? effectiveFrom : this.effectiveFrom;
        return new PolicyVersion(
            policyVersionId, policyId, versionNumber, target, rules, nextEffectiveFrom, effectiveTo,
            createdBy, nextReviewedBy, nextPublishedBy, nextPublishedAt
        );
    }

    /** Guards INV-PG-006: rules can never be replaced once this version is {@code PUBLISHED}. */
    public PolicyVersion withRules(List<PolicyRule> newRules) {
        if (status == PolicyStatus.PUBLISHED) {
            throw new PolicyVersionImmutableException(policyVersionId);
        }
        return new PolicyVersion(
            policyVersionId, policyId, versionNumber, status, newRules, effectiveFrom, effectiveTo,
            createdBy, reviewedBy, publishedBy, publishedAt
        );
    }

    public String policyVersionId() {
        return policyVersionId;
    }

    public String policyId() {
        return policyId;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public PolicyStatus status() {
        return status;
    }

    public List<PolicyRule> rules() {
        return rules;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant effectiveTo() {
        return effectiveTo;
    }

    public String createdBy() {
        return createdBy;
    }

    public String reviewedBy() {
        return reviewedBy;
    }

    public String publishedBy() {
        return publishedBy;
    }

    public Instant publishedAt() {
        return publishedAt;
    }
}
