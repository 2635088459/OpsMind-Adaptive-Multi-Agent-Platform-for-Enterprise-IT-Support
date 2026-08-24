package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when a use command targets an override that is already {@code
 * USED} by a different attempt (a different {@code commandIdempotencyKey}).
 * SPEC-PG-022, mirroring {@link ApprovalAlreadyCancelledException}'s own
 * reasoning: an identical retry (same {@code commandIdempotencyKey}) is
 * handled as an idempotent replay instead — see {@code
 * ApprovalService#use}.
 */
public class OverrideAlreadyUsedException extends RuntimeException {

    public OverrideAlreadyUsedException(String approvalRequestId) {
        super("override " + approvalRequestId + " is already used by a different attempt");
    }
}
