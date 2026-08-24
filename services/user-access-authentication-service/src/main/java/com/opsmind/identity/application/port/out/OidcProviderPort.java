package com.opsmind.identity.application.port.out;

/**
 * 13-package-and-class-design §Output Ports. Real Keycloak discovery,
 * authorization-code+PKCE exchange, and end-session calls are SPEC-UA-004's
 * (OIDC Provider Discovery And Configuration) and SPEC-UA-005's job. The
 * SPEC-UA-001-scoped adapter under {@code infrastructure.keycloak} always
 * reports unavailable, so any caller depending on this port fails closed
 * rather than silently no-op-succeeding.
 */
public interface OidcProviderPort {

    boolean isAvailable();

    /** Best-effort remote logout notification (08-transaction-and-outbox: "best-effort notifies the IdP", local revocation already happened first). */
    void requestEndSession(String idpSessionIdHash);
}
