package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when a policy version's own author attempts to publish it.
 * SPEC-PG-001 test-plan security test: "policy author cannot publish their
 * own unreviewed policy."
 */
public class PolicyPublishSeparationOfDutiesException extends RuntimeException {

    public PolicyPublishSeparationOfDutiesException(String policyVersionId) {
        super("policy version " + policyVersionId + " cannot be published by its own author");
    }
}
