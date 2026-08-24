package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.policy.PolicyVersion;

import java.time.Instant;
import java.util.Optional;

/** Port for {@link PolicyVersion} snapshot persistence. */
public interface PolicyVersionRepository {

    PolicyVersion save(PolicyVersion policyVersion);

    Optional<PolicyVersion> findById(String policyVersionId);

    /** Selects the {@code PUBLISHED} version effective at {@code asOf} (04-use-cases §UC-PG-001 step 2). */
    Optional<PolicyVersion> findEffectiveVersion(String policyId, Instant asOf);

    /**
     * SPEC-PG-019 (goal: "rule fixes require new versions"): the
     * highest-numbered version this policy has, regardless of its status —
     * {@code application.PolicyAdminService#draft} uses this to compute the
     * next version number, since two versions of the same policy can never
     * share a number ({@code uq_policy_versions_policy_version}).
     */
    Optional<PolicyVersion> findLatestVersion(String policyId);

    /**
     * SPEC-PG-020: looks up a specific version by its business key
     * (policyId + versionNumber) rather than its generated {@code
     * policyVersionId} — {@code application.PolicyAdminService#publish}
     * uses this to find the policy's previously {@code PUBLISHED} version
     * (recorded only as a plain version number on {@code Policy#currentPublishedVersion})
     * so it can transition that version to {@code SUPERSEDED}.
     */
    Optional<PolicyVersion> findByPolicyIdAndVersionNumber(String policyId, int versionNumber);
}
