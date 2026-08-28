package com.opsmind.identity.application.exception;

/**
 * INV-UA-005 ("Step-up evidence binds issuer, subject, session, action,
 * resource, assurance, and expiry"); 03-state-machine §StepUpChallenge:
 * "Action/resource mismatch preserves state and writes a denial audit."
 * Thrown when the caller attempting to consume a proof asserts a different
 * action/resource than the one the challenge was originally requested for —
 * the challenge itself is left untouched (never transitioned, never
 * attempt-counted), unlike an actual illegal state transition.
 */
public class StepUpBindingMismatchException extends RuntimeException {

    public StepUpBindingMismatchException(String stepUpChallengeId) {
        super("step-up challenge " + stepUpChallengeId + " is bound to a different action/resource");
    }
}
