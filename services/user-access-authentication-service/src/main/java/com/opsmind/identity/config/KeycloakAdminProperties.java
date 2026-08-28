package com.opsmind.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SPEC-UA-009 (04-use-cases §Logout/revocation: "request IdP end-session/
 * revocation"). A separate client registration from the browser-login one
 * (11-security: secrets never appear in source configuration — injected via
 * environment only, see {@code application-local.yml}'s own comment).
 * {@code clientId}/{@code clientSecret} empty (the default) disables real
 * end-session notification entirely — {@code
 * infrastructure.keycloak.KeycloakOidcProviderAdapter} then treats every
 * revoke as a graceful, already-"notified" no-op rather than an endlessly-
 * retried failure, since without credentials the call can never succeed.
 */
@ConfigurationProperties(prefix = "app.identity.oidc.admin")
public record KeycloakAdminProperties(String clientId, String clientSecret) {

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
