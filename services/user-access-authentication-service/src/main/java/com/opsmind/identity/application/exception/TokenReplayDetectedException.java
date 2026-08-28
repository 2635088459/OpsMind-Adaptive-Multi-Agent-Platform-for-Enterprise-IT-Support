package com.opsmind.identity.application.exception;

/**
 * SPEC-UA-034 (07-data-model §user_sessions: "UNIQUE session hash";
 * 11-security: "Threat modeling covers token substitution/replay/theft").
 * Thrown when {@link com.opsmind.identity.application.service.ManageSessionService#start}
 * is asked to start a session for a {@code tokenIdHash} that already backs
 * an existing session — the same bearer token being replayed to mint a
 * second one. Deliberately distinct from the generic {@code
 * DataIntegrityViolationException}→409 handler (which would otherwise
 * treat this identically to an ordinary optimistic-concurrency conflict):
 * this is a genuine security signal, not a retryable race.
 */
public class TokenReplayDetectedException extends RuntimeException {

    public TokenReplayDetectedException() {
        super("a session already exists for this token — refusing to start a second one");
    }
}
