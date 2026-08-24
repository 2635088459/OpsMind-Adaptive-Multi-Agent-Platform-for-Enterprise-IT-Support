package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainException;

/**
 * Thrown when {@link ApprovalRequest#use} is called past the request's own
 * {@code expiresAt}. UC-PG-006 (04-use-cases): "Override is valid only
 * within limited scope and time window." An approved override sitting past
 * its expiry has not necessarily been formally transitioned to {@code
 * EXPIRED} yet (the expiry worker only scans {@code REQUESTED} rows,
 * 03-state-machine {@code REQUESTED -> EXPIRED}) — {@code use} enforces the
 * time-window limit itself rather than trusting that scan to have already
 * run.
 */
public class OverrideExpiredException extends DomainException {

    public OverrideExpiredException(String approvalRequestId) {
        super("override " + approvalRequestId + " is past its expiresAt and can no longer be used");
    }

    @Override
    public String code() {
        return "OVERRIDE_EXPIRED";
    }
}
