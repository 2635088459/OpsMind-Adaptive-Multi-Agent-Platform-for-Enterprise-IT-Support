package com.opsmind.policygovernance.domain.policy;

import com.opsmind.policygovernance.domain.shared.DomainException;

/** Thrown when a {@link PolicyVersion} transition is not allowed from its current {@link PolicyStatus}. */
public class IllegalPolicyTransitionException extends DomainException {

    private final PolicyStatus currentStatus;
    private final PolicyStatus targetStatus;

    public IllegalPolicyTransitionException(PolicyStatus currentStatus, PolicyStatus targetStatus) {
        super("cannot transition policy version from " + currentStatus + " to " + targetStatus);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public PolicyStatus currentStatus() {
        return currentStatus;
    }

    public PolicyStatus targetStatus() {
        return targetStatus;
    }

    @Override
    public String code() {
        return "ILLEGAL_POLICY_TRANSITION";
    }
}
