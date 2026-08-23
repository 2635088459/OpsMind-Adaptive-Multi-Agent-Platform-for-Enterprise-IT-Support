package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when a cancel command targets an approval request that is already
 * {@code CANCELLED} by a different attempt (a different {@code
 * commandIdempotencyKey}). SPEC-PG-012, mirroring {@link
 * ApprovalAlreadyDecidedException}'s own reasoning for grant/deny: "the
 * first transaction that commits a final decision succeeds; later requests
 * return the existing final decision; conflicting payload returns
 * conflict." An identical retry (same {@code commandIdempotencyKey}) is
 * handled as an idempotent replay instead — see {@code
 * ApprovalService#cancel}.
 */
public class ApprovalAlreadyCancelledException extends RuntimeException {

    public ApprovalAlreadyCancelledException(String approvalRequestId) {
        super("approval request " + approvalRequestId + " is already cancelled by a different attempt");
    }
}
