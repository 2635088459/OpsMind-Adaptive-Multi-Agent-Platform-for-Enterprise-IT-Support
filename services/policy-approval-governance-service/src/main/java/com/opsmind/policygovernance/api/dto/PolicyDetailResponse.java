package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;

import java.util.List;

/** {@code GET /api/v1/policies/{policyId}}: the header plus every version, newest first (rules included per version). */
public record PolicyDetailResponse(
    PolicySummaryResponse policy,
    List<PolicyVersionResponse> versions
) {

    public static PolicyDetailResponse of(Policy policy, List<PolicyVersion> versions) {
        return new PolicyDetailResponse(
            PolicySummaryResponse.from(policy),
            versions.stream().map(PolicyVersionResponse::from).toList()
        );
    }
}
