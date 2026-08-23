package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when the deciding actor is not an authorized approver for the
 * approval type, or is the same actor who requested it (self-approval —
 * SPEC-PG-001 test-plan security test: "requester cannot approve their own
 * request").
 */
public class ApprovalNotAuthorizedException extends RuntimeException {

    public ApprovalNotAuthorizedException(String message) {
        super(message);
    }
}
