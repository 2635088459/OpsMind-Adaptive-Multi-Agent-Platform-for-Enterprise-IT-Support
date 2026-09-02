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
    String failureRedirectUri,
    /**
     * SPEC-SC-001: domain 10's own support-console logs in through a second,
     * distinct registration ("support-console") — a single fixed {@code
     * successRedirectUri} can only ever point at one frontend's own origin,
     * so {@link BrowserLoginSuccessHandler} picks between this and the field
     * above by checking which registration the completed login actually
     * used, per this class's own already-anticipated comment on {@code
     * successRedirectUri} ("each app's own login entry point sets state to
     * route back to itself if needed" — resolved here by registration id
     * instead, simpler and sufficient for exactly 2 known frontends).
     */
    String supportConsoleSuccessRedirectUri
) {

    public BrowserLoginProperties {
        defaultTenantId = (defaultTenantId == null || defaultTenantId.isBlank()) ? "opsmind" : defaultTenantId;
        sessionTtl = sessionTtl == null ? Duration.ofHours(8) : sessionTtl;
        sessionCookieName = (sessionCookieName == null || sessionCookieName.isBlank()) ? "OPSMIND_SESSION" : sessionCookieName;
        successRedirectUri = (successRedirectUri == null || successRedirectUri.isBlank()) ? "/" : successRedirectUri;
        failureRedirectUri = (failureRedirectUri == null || failureRedirectUri.isBlank()) ? "/login?error" : failureRedirectUri;
        supportConsoleSuccessRedirectUri = (supportConsoleSuccessRedirectUri == null || supportConsoleSuccessRedirectUri.isBlank())
            ? successRedirectUri
            : supportConsoleSuccessRedirectUri;
    }
}
