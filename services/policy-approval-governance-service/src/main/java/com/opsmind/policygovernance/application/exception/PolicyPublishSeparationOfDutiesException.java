package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when a policy version's own author or reviewer attempts to publish
 * it. SPEC-PG-001 test-plan security test: "policy author cannot publish
 * their own unreviewed policy." SPEC-PG-018 (goal: "reviewer/publisher
 * separation of duties") extends the same guard to the reviewer — a
 * "review" step that the same person can also publish is not a real second
 * pair of eyes.
 */
public class PolicyPublishSeparationOfDutiesException extends RuntimeException {

    public PolicyPublishSeparationOfDutiesException(String policyVersionId, String conflictingRole) {
        super("policy version " + policyVersionId + " cannot be published by its own " + conflictingRole);
    }
}
