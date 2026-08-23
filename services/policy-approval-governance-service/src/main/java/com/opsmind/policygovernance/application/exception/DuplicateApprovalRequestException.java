package com.opsmind.policygovernance.application.exception;

/** Thrown when {@code requestKey} is reused with a different {@code requestHash} (idempotency conflict, not a replay). */
public class DuplicateApprovalRequestException extends RuntimeException {

    public DuplicateApprovalRequestException(String requestKey) {
        super("requestKey " + requestKey + " was already used with a different request payload");
    }
}
