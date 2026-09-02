package com.opsmind.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The domain 09/10 frontend (a separate origin, e.g. {@code
 * http://localhost:5173} in local dev) needs real cross-origin access to
 * {@link com.opsmind.identity.api.browser.BrowserSessionTokenController}'s
 * session-cookie-authenticated endpoint — Keycloak's own callback redirect
 * lands directly on this service's own origin (never through any frontend
 * dev-server proxy), so the session cookie is set scoped to this origin and
 * a proxy cannot rewrite that after the fact; genuine CORS + {@code
 * SameSite=None} cookies is the only real fix, not a local-dev shortcut.
 * Empty by default (deny by default, INV-UA-002) — an operator opts specific
 * frontend origins in.
 */
@ConfigurationProperties(prefix = "app.identity.cors")
public record BrowserCorsProperties(List<String> allowedOrigins) {

    public BrowserCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
