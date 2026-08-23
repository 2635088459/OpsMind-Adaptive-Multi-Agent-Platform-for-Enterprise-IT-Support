package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when a grant/deny command targets an approval request that already
 * has a final decision made by a different attempt (different outcome
 * and/or decider). SPEC-PG-003 (09-concurrency-and-idempotency): "the first
 * transaction that commits a final decision succeeds; later requests
 * return the existing final decision; conflicting payload returns
 * conflict." An identical retry (same outcome, same decider) is handled as
 * an idempotent replay instead — see {@code ApprovalService#decide}.
 */
public class ApprovalAlreadyDecidedException extends RuntimeException {

    public ApprovalAlreadyDecidedException(String approvalRequestId) {
        super("approval request " + approvalRequestId + " already has a final decision that does not match this request");
    }
}
