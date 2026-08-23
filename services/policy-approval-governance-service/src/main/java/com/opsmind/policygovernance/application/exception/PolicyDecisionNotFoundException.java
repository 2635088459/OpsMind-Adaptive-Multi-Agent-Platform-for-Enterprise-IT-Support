package com.opsmind.policygovernance.application.exception;

public class PolicyDecisionNotFoundException extends RuntimeException {

    public PolicyDecisionNotFoundException(String policyDecisionId) {
        super("policy decision " + policyDecisionId + " was not found");
    }
}
