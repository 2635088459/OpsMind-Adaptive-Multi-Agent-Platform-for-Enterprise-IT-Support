package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

/**
 * Port to identity/authorization checks for approvers and policy
 * reviewers/publishers (11-security §Permission Model: "RBAC decides
 * whether a user can approve, publish policy, or view audit. ABAC decides
 * whether that principal may act for a specific ticket, tenant, resource,
 * and risk level."). SPEC-PG-014 gives this a real implementation
 * ({@code infrastructure.identity.JwtIdentityAuthorizationAdapter}), reading
 * OAuth2 scopes for the RBAC half and a risk-clearance claim for the ABAC
 * half — see that adapter's own javadoc for exactly what it checks and what
 * it deliberately still defers (per-ticket/tenant/resource ABAC has no
 * modeled attribute anywhere in this service yet). The fuller
 * requester/executor/approver relationship model behind {@link
 * #isIndependentApprover} beyond simple identity inequality is SPEC-PG-015's
 * own job (Separation Of Duties Check).
 */
public interface IdentityAuthorizationPort {

    /**
     * {@code riskLevel} is the ABAC half of this check — an actor with the
     * required RBAC scope for {@code approvalType} but insufficient risk
     * clearance for this specific request must still be refused.
     */
    boolean isAuthorizedApprover(String actorId, ApprovalType approvalType, RiskLevel riskLevel);

    /** True only if {@code approverId} is independent of {@code requesterId} per separation-of-duties policy. */
    boolean isIndependentApprover(String requesterId, String approverId);
}
