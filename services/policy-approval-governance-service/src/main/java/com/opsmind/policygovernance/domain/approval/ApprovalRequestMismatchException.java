package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainException;

/**
 * Thrown when a decision or cancel command does not match the {@link
 * ApprovalRequest} it targets. INV-PG-005: "Approval Is Valid Only For
 * Matching Requests" — a command applies only to the exact {@code
 * approvalRequestId + sourceRequestId + requestHash} triple. SPEC-PG-012
 * reuses this for {@code ApprovalService#cancel}, not just grant/deny.
 */
public class ApprovalRequestMismatchException extends DomainException {

    public ApprovalRequestMismatchException(String approvalRequestId) {
        super("command does not match approval request " + approvalRequestId + " (sourceRequestId/requestHash mismatch)");
    }

    @Override
    public String code() {
        return "APPROVAL_REQUEST_MISMATCH";
    }
}
