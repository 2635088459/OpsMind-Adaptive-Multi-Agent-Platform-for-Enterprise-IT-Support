package com.opsmind.policygovernance.application.exception;

public class ApprovalRequestNotFoundException extends RuntimeException {

    public ApprovalRequestNotFoundException(String approvalRequestId) {
        super("approval request " + approvalRequestId + " was not found");
    }
}
