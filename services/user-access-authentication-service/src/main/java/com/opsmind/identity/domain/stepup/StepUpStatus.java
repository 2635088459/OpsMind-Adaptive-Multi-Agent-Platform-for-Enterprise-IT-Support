package com.opsmind.identity.domain.stepup;

/**
 * 03-state-machine §StepUpChallenge.
 *
 * <pre>
 *   REQUESTED --dispatch--> PENDING --verify--> VERIFIED --consume--> CONSUMED
 *                              |--attempt limit--> FAILED
 *                              |--timeout--------> EXPIRED
 *                              `--cancel---------> CANCELLED
 * </pre>
 */
public enum StepUpStatus {
    REQUESTED,
    PENDING,
    VERIFIED,
    CONSUMED,
    FAILED,
    EXPIRED,
    CANCELLED
}
