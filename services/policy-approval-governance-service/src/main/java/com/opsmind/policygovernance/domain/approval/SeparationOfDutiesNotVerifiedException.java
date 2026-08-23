package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainException;

/**
 * Thrown when an {@link ApprovalDecision} would grant an approval without a
 * passed separation-of-duties check. INV-PG-004: "Requester, executor, and
 * approver must not violate separation-of-duties policy." The full
 * requester/executor/approver identity check is owned by SPEC-PG-011
 * (Approval Grant Deny API) and phase-03 (Security / Separation Of Duties);
 * this guard only refuses to let an APPROVED decision exist without the
 * check flag having been set by that caller.
 */
public class SeparationOfDutiesNotVerifiedException extends DomainException {

    public SeparationOfDutiesNotVerifiedException(String approvalRequestId) {
        super("approval request " + approvalRequestId + " cannot be granted without a passed separation-of-duties check");
    }

    @Override
    public String code() {
        return "SEPARATION_OF_DUTIES_NOT_VERIFIED";
    }
}
