package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.application.RecoveryService;

/** SPEC-PG-033: one {@code RecoveryService#checkPolicyVersionConsistency} finding. */
public record PolicyVersionConsistencyFindingResponse(String policyId, int versionNumber, String issue) {

    public static PolicyVersionConsistencyFindingResponse from(RecoveryService.PolicyVersionConsistencyFinding finding) {
        return new PolicyVersionConsistencyFindingResponse(finding.policyId(), finding.versionNumber(), finding.issue());
    }
}
