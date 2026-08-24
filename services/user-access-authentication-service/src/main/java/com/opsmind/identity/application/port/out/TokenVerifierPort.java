package com.opsmind.identity.application.port.out;

/**
 * 13-package-and-class-design §Output Ports. Baseline issuer/audience/
 * signature/expiry validation on every HTTP request is already real —
 * Spring's standard OAuth2 resource server ({@code config.SecurityConfig}).
 * This port is for supplementary, programmatic verification an application
 * service needs off the request thread (e.g. verifying a token referenced
 * by id during step-up). Real JWKS-backed verification is SPEC-UA-006's job
 * (JWT Validation JWKS Rotation); the SPEC-UA-001-scoped adapter always
 * reports invalid, failing closed.
 */
public interface TokenVerifierPort {

    boolean isValid(String tokenIdHash);
}
