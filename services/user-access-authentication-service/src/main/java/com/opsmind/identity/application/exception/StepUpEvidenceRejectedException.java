package com.opsmind.identity.application.exception;

/**
 * SPEC-UA-018 (Step Up Proof Verification — INV-UA-005: "Step-up evidence
 * binds issuer, subject, session, action, resource, assurance, and expiry").
 * Thrown when real re-authentication evidence fails any of that binding: a
 * different subject re-authenticated than the one the challenge was
 * requested for, the nonce does not match this specific challenge, or the
 * achieved {@code acr}/{@code amr} does not meet what the challenge itself
 * requires. Counted as a failed verification attempt, same as any other
 * rejected verification.
 */
public class StepUpEvidenceRejectedException extends RuntimeException {

    public StepUpEvidenceRejectedException(String stepUpChallengeId, String reason) {
        super("step-up challenge " + stepUpChallengeId + " rejected the presented evidence: " + reason);
    }
}
