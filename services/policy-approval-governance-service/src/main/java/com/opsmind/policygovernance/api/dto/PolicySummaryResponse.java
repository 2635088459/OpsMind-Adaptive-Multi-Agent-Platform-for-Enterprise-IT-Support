package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyLifecycleStatus;

import java.time.Instant;

/** One row of {@code GET /api/v1/policies}: the policy header, no rule content. */
public record PolicySummaryResponse(
    String policyId,
    String policyName,
    String scope,
    Integer currentPublishedVersion,
    PolicyLifecycleStatus status,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {

    public static PolicySummaryResponse from(Policy policy) {
        return new PolicySummaryResponse(
            policy.policyId(), policy.policyName(), policy.scope(), policy.currentPublishedVersion(),
            policy.status(), policy.createdBy(), policy.createdAt(), policy.updatedAt()
        );
    }
}
