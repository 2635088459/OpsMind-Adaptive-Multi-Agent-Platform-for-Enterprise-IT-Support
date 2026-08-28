package com.opsmind.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SPEC-UA-018 (Step Up Proof Verification — 05-api-contracts {@code POST
 * /step-up/challenges}: "returns challengeId, redirect, expiresAt"). A
 * separate OAuth2 client registration from {@code opsmind} (SPEC-UA-005's
 * own primary-login one): the same underlying Keycloak client, registered
 * with a second valid {@code redirect-uri} for this flow, forced through
 * {@code prompt=login} to obtain a genuinely fresh re-authentication rather
 * than reusing an existing Keycloak SSO session — see {@code
 * StepUpAuthorizationRequestResolver}'s own javadoc.
 */
@ConfigurationProperties(prefix = "app.identity.step-up")
public record StepUpVerificationProperties(
    String successRedirectUri,
    String failureRedirectUri
) {

    /** The {@code spring.security.oauth2.client.registration} id this flow's own {@code SecurityFilterChain} routes recognize. */
    public static final String REGISTRATION_ID = "opsmind-stepup";

    public StepUpVerificationProperties {
        successRedirectUri = (successRedirectUri == null || successRedirectUri.isBlank()) ? "/" : successRedirectUri;
        failureRedirectUri = (failureRedirectUri == null || failureRedirectUri.isBlank()) ? "/step-up?error" : failureRedirectUri;
    }
}
