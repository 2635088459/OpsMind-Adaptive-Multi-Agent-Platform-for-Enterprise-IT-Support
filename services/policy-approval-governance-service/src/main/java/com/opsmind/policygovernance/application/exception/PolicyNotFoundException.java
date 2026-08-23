package com.opsmind.policygovernance.application.exception;

public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(String policyId) {
        super("policy " + policyId + " was not found");
    }
}
