package com.opsmind.policygovernance.infrastructure.identity;

import com.opsmind.policygovernance.application.port.IdentityAuthorizationPort;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import org.springframework.stereotype.Component;

/**
 * Fail-closed placeholder behind {@link IdentityAuthorizationPort}. The real
 * permission model, approver role mapping, and requester/executor/approver
 * separation-of-duties evaluation are owned by phase-03 (Security /
 * Separation Of Duties) and SPEC-PG-011.
 *
 * <p>Until wired to a real identity provider, this adapter denies every
 * approval and treats no approver as independent — a governance service
 * failing closed on authorization is the only safe default (mirrors the
 * evaluator's own fail-safe posture in {@code
 * infrastructure.evaluator.DefaultRuleEvaluatorAdapter}).
 */
@Component
public class StubIdentityAuthorizationAdapter implements IdentityAuthorizationPort {

    @Override
    public boolean isAuthorizedApprover(String actorId, ApprovalType approvalType) {
        return false;
    }

    @Override
    public boolean isIndependentApprover(String requesterId, String approverId) {
        return false;
    }
}
