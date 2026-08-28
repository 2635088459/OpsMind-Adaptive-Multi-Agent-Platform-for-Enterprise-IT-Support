package com.opsmind.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SPEC-UA-005 (04-use-cases §OIDC login). {@code defaultTenantId}: no
 * multi-tenant realm-per-tenant claim convention exists anywhere in this
 * platform yet — every service targets the same shared Keycloak {@code
 * opsmind} realm — so a fixed, server-side-configured tenant id (never a
 * client-supplied one, per 02-business-invariants #7) is the honest
 * interpretation until a real tenant claim/mapping exists.
 */
@ConfigurationProperties(prefix = "app.identity.login")
public record BrowserLoginProperties(
    String defaultTenantId,
    Duration sessionTtl,
    String sessionCookieName,
    String successRedirectUri,
    String failureRedirectUri
) {

    public BrowserLoginProperties {
        defaultTenantId = (defaultTenantId == null || defaultTenantId.isBlank()) ? "opsmind" : defaultTenantId;
        sessionTtl = sessionTtl == null ? Duration.ofHours(8) : sessionTtl;
        sessionCookieName = (sessionCookieName == null || sessionCookieName.isBlank()) ? "OPSMIND_SESSION" : sessionCookieName;
        successRedirectUri = (successRedirectUri == null || successRedirectUri.isBlank()) ? "/" : successRedirectUri;
        failureRedirectUri = (failureRedirectUri == null || failureRedirectUri.isBlank()) ? "/login?error" : failureRedirectUri;
    }
}
