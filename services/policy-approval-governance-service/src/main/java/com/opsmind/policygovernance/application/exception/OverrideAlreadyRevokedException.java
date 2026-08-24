package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when a revoke command targets an override that is already {@code
 * REVOKED} by a different attempt (a different {@code
 * commandIdempotencyKey}). SPEC-PG-022, mirroring {@link
 * ApprovalAlreadyCancelledException}'s own reasoning: an identical retry
 * (same {@code commandIdempotencyKey}) is handled as an idempotent replay
 * instead — see {@code ApprovalService#revoke}.
 */
public class OverrideAlreadyRevokedException extends RuntimeException {

    public OverrideAlreadyRevokedException(String approvalRequestId) {
        super("override " + approvalRequestId + " is already revoked by a different attempt");
    }
}
