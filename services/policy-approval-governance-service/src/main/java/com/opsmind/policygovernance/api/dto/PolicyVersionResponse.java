package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;

import java.time.Instant;
import java.util.List;

public record PolicyVersionResponse(
    String policyVersionId,
    String policyId,
    int versionNumber,
    PolicyStatus status,
    List<PolicyRuleDto> rules,
    Instant effectiveFrom,
    Instant effectiveTo,
    String createdBy,
    String reviewedBy,
    String publishedBy,
    Instant publishedAt
) {

    public static PolicyVersionResponse from(PolicyVersion version) {
        return new PolicyVersionResponse(
            version.policyVersionId(), version.policyId(), version.versionNumber(), version.status(),
            version.rules().stream().map(PolicyRuleDto::from).toList(),
            version.effectiveFrom(), version.effectiveTo(), version.createdBy(),
            version.reviewedBy(), version.publishedBy(), version.publishedAt()
        );
    }
}
