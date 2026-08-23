package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainException;

/**
 * Thrown when a decision command does not match the {@link ApprovalRequest}
 * it targets. INV-PG-005: "Approval Is Valid Only For Matching Requests" —
 * an approval decision applies only to the exact {@code approvalRequestId +
 * sourceRequestId + requestHash} triple.
 */
public class ApprovalRequestMismatchException extends DomainException {

    public ApprovalRequestMismatchException(String approvalRequestId) {
        super("decision does not match approval request " + approvalRequestId + " (sourceRequestId/requestHash mismatch)");
    }

    @Override
    public String code() {
        return "APPROVAL_REQUEST_MISMATCH";
    }
}
