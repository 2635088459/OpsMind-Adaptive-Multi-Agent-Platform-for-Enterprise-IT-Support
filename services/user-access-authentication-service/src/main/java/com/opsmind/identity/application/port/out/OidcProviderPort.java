package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.user.ExternalSubject;

/**
 * 13-package-and-class-design §Output Ports. Real Keycloak discovery is
 * SPEC-UA-004's job; real end-session notification (SPEC-UA-009, Session
 * Refresh Logout And Revocation) is opt-in and best-effort — see {@code
 * infrastructure.keycloak.KeycloakOidcProviderAdapter}'s own javadoc.
 */
public interface OidcProviderPort {

    boolean isAvailable();

    /**
     * Best-effort remote logout notification (08-transaction-and-outbox:
     * "best-effort notifies the IdP", local revocation already happened
     * first). Takes {@code externalSubject} rather than a hash: a real
     * Keycloak Admin API call needs the actual {@code sub} claim (already
     * stored in plaintext as part of {@link ExternalSubject} — only
     * session/token *identifiers* are hashed per INV-UA-001, never the
     * subject itself), not a session hash. Throws on failure so the caller
     * can track "not yet notified" for retry (10-failure-handling: "Keep
     * local revocation and retry if IdP is unavailable") — never silently
     * swallows a real failure.
     */
    void requestEndSession(ExternalSubject externalSubject);
}
