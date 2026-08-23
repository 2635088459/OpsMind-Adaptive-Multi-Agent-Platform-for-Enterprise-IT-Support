package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.IdentityAuthorizationPort;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

/**
 * Test double for {@link IdentityAuthorizationPort}: authorizes any actor
 * for any risk level and treats a different actor from the requester as
 * independent, unlike the fail-closed {@code JwtIdentityAuthorizationAdapter}
 * production adapter. Lets application-layer tests exercise the happy path.
 */
public class FakeIdentityAuthorizationPort implements IdentityAuthorizationPort {

    @Override
    public boolean isAuthorizedApprover(String actorId, ApprovalType approvalType, RiskLevel riskLevel) {
        return true;
    }

    @Override
    public boolean isIndependentApprover(String requesterId, String approverId) {
        return !requesterId.equals(approverId);
    }
}
