package com.opsmind.policygovernance.domain.policy;

/**
 * Top-level lifecycle status of a {@link Policy} header — distinct from
 * {@link PolicyStatus}, which tracks an individual {@link PolicyVersion}'s
 * draft/review/publish state. 07-data-model lists a {@code status} column
 * on the {@code policies} table itself; this is that column's domain.
 *
 * <p>A full policy-level retirement workflow (who may retire a policy, what
 * happens to its still-published version) belongs to phase-04 (Policy Admin
 * And Versioning); this spec only carries the field so the column is never
 * a silently-NULL, domain-model-less persistence artifact.
 */
public enum PolicyLifecycleStatus {
    ACTIVE,
    RETIRED
}
