package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainException;

/**
 * Thrown when {@link ApprovalRequest#use} or {@link ApprovalRequest#revoke}
 * is called on a request whose {@link ApprovalType} is not {@link
 * ApprovalType#POLICY_OVERRIDE}. 03-state-machine draws the {@code
 * OVERRIDE_APPROVED -> OVERRIDE_USED / OVERRIDE_REVOKED} continuation as a
 * separate "Override State Machine," distinct from the general Approval
 * Request State Machine every other approval type follows — an ordinary
 * {@code TOOL_EXECUTION}/{@code TICKET_ACTION}/{@code WORKFLOW_ACTION}/{@code
 * GENERIC} approval has no "used"/"revoked" concept to transition into.
 */
public class NotAnOverrideRequestException extends DomainException {

    public NotAnOverrideRequestException(String approvalRequestId, ApprovalType actualType) {
        super("approval request " + approvalRequestId + " is not a POLICY_OVERRIDE request (actual type: " + actualType + ")");
    }

    @Override
    public String code() {
        return "NOT_AN_OVERRIDE_REQUEST";
    }
}
