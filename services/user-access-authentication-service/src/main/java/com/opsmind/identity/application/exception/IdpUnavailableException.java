package com.opsmind.identity.application.exception;

/**
 * SPEC-UA-032 (10-failure-handling: "Keycloak unavailable | Policy may
 * briefly continue already validated, unexpired low-risk sessions; new
 * login, step-up, and sensitive actions return 503/fail closed"). Thrown
 * when {@link com.opsmind.identity.application.port.out.OidcProviderPort#isAvailable()}
 * reports the configured issuer cannot currently be trusted, and the
 * caller is about to start a NEW sensitive action that this domain cannot
 * meaningfully begin without that trust (a fresh step-up challenge, a
 * break-glass activation) — deliberately distinct from every other denial
 * in this domain (403 "not authorized"), since this reflects a transient
 * infrastructure condition, not a permanent lack of authority: mapped to
 * 503, retryable.
 */
public class IdpUnavailableException extends RuntimeException {

    public IdpUnavailableException(String action) {
        super("cannot start " + action + ": IdP availability could not be confirmed");
    }
}
