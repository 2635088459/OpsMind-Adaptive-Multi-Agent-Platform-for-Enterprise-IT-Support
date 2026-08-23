package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.approval.ApprovalType;

/**
 * Port to identity/authorization checks for approvers and policy
 * reviewers/publishers. The real permission model and requester/executor/
 * approver separation-of-duties evaluation belong to phase-03 (Security /
 * Separation Of Duties) and SPEC-PG-011; {@code
 * infrastructure.identity.StubIdentityAuthorizationAdapter} fails closed
 * (denies) until those specs wire a real identity provider.
 */
public interface IdentityAuthorizationPort {

    boolean isAuthorizedApprover(String actorId, ApprovalType approvalType);

    /** True only if {@code approverId} is independent of {@code requesterId} per separation-of-duties policy. */
    boolean isIndependentApprover(String requesterId, String approverId);
}
