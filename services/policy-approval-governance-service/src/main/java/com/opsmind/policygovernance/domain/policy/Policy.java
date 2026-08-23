package com.opsmind.policygovernance.domain.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * The named, versioned governance policy header (01-domain-model §Policy —
 * "Policy is a versioned collection of governance rules"). The concrete
 * rule set for each version lives in {@link PolicyVersion}, matching the
 * separate {@code policies}/{@code policy_versions} tables in
 * 07-data-model; this type only tracks which version is currently
 * published and whether the policy as a whole is retired, not rule content.
 */
public record Policy(
    String policyId,
    String policyName,
    String scope,
    Integer currentPublishedVersion,
    PolicyLifecycleStatus status,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {

    public Policy {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(policyName, "policyName");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Policy created(String policyId, String policyName, String scope, String createdBy, Instant now) {
        return new Policy(policyId, policyName, scope, null, PolicyLifecycleStatus.ACTIVE, createdBy, now, now);
    }

    public Policy withCurrentPublishedVersion(int versionNumber, Instant now) {
        return new Policy(policyId, policyName, scope, versionNumber, status, createdBy, createdAt, now);
    }

    public Policy retire(Instant now) {
        return new Policy(policyId, policyName, scope, currentPublishedVersion, PolicyLifecycleStatus.RETIRED, createdBy, createdAt, now);
    }
}
