package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.IdentityAuthorizationPort;
import com.opsmind.policygovernance.domain.approval.ApprovalType;

/**
 * Test double for {@link IdentityAuthorizationPort}: authorizes any actor
 * and treats a different actor from the requester as independent, unlike
 * the fail-closed {@code StubIdentityAuthorizationAdapter} production
 * placeholder. Lets application-layer tests exercise the happy path.
 */
public class FakeIdentityAuthorizationPort implements IdentityAuthorizationPort {

    @Override
    public boolean isAuthorizedApprover(String actorId, ApprovalType approvalType) {
        return true;
    }

    @Override
    public boolean isIndependentApprover(String requesterId, String approverId) {
        return !requesterId.equals(approverId);
    }
}
