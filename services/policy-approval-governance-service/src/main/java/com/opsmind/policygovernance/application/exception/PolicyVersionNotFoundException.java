package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when no effective {@code PUBLISHED} policy version can be found for
 * a decision request. Handled as a fail-safe {@code DENY}, never a silent
 * allow — SPEC-PG-001 domain rule "Default allow on policy evaluator
 * failure" is forbidden.
 */
public class PolicyVersionNotFoundException extends RuntimeException {

    public PolicyVersionNotFoundException(String policyId) {
        super("no effective PUBLISHED policy version found for policy " + policyId);
    }
}
