package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when {@code decisionKey} is reused with a different {@code
 * inputHash}. SPEC-PG-003 (09-concurrency-and-idempotency): "Same
 * decisionKey with different input hash returns conflict, preventing
 * downstream from overwriting different facts using one business key."
 */
public class DecisionKeyConflictException extends RuntimeException {

    public DecisionKeyConflictException(String decisionKey) {
        super("decisionKey " + decisionKey + " was already used with a different inputHash");
    }
}
