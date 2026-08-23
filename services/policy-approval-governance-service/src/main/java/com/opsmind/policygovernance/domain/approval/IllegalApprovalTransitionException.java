package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainException;

/**
 * Thrown when an {@link ApprovalRequest} not currently {@code REQUESTED} is
 * asked to approve/deny/cancel/expire/supersede. 03-state-machine: "Only
 * REQUESTED can be approved or denied. Final states are irreversible."
 */
public class IllegalApprovalTransitionException extends DomainException {

    private final ApprovalStatus currentStatus;

    public IllegalApprovalTransitionException(ApprovalStatus currentStatus, ApprovalStatus targetStatus) {
        super("approval request in status " + currentStatus + " cannot transition to " + targetStatus + "; final states are irreversible");
        this.currentStatus = currentStatus;
    }

    public ApprovalStatus currentStatus() {
        return currentStatus;
    }

    @Override
    public String code() {
        return "ILLEGAL_APPROVAL_TRANSITION";
    }
}
