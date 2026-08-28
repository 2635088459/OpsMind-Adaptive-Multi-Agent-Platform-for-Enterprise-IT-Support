package com.opsmind.identity.infrastructure.keycloak;

import com.opsmind.identity.application.port.out.OidcProviderPort;
import com.opsmind.identity.config.KeycloakAdminProperties;
import com.opsmind.identity.config.OidcIssuerProperties;
import com.opsmind.identity.domain.user.ExternalSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * SPEC-UA-004: replaces SPEC-UA-001's always-unavailable placeholder.
 * {@link #isAvailable} is real — it reflects whether this issuer's
 * discovery document has been fetched successfully within the configured
 * TTL, so a caller that gates a sensitive action on IdP trust (09-concurrency-and-idempotency:
 * "Sensitive operations deny when ... IdP trust fails") gets a genuine
 * signal, not a hardcoded one.
 *
 * <p>SPEC-UA-009: {@link #requestEndSession} is a real Keycloak Admin API
 * call ({@link KeycloakAdminClient}) once {@link KeycloakAdminProperties}
 * is configured — a separate opt-in client-credentials registration, empty
 * by default. Unconfigured means "no admin API available," treated as a
 * graceful, already-"notified" no-op (never an endlessly-retried failure a
 * missing credential can never fix); configured-but-failing throws, so the
 * caller (SPEC-UA-009's own retry/reconciliation) can tell the two cases
 * apart and only retry the latter.
 */
@Component
public class KeycloakOidcProviderAdapter implements OidcProviderPort {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOidcProviderAdapter.class);

    private final OidcDiscoveryClient discoveryClient;
    private final KeycloakAdminClient adminClient;
    private final String issuerUri;
    private final KeycloakAdminProperties adminProperties;
    private final Duration cacheTtl;
    private final Clock clock;

    private volatile Instant lastSuccessfulFetch;

    public KeycloakOidcProviderAdapter(
        OidcDiscoveryClient discoveryClient, KeycloakAdminClient adminClient,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
        OidcIssuerProperties properties, KeycloakAdminProperties adminProperties, Clock clock
    ) {
        this.discoveryClient = discoveryClient;
        this.adminClient = adminClient;
        this.issuerUri = issuerUri;
        this.adminProperties = adminProperties;
        this.cacheTtl = properties.discoveryCacheTtl();
        this.clock = clock;
    }

    @Override
    public boolean isAvailable() {
        Instant now = clock.instant();
        Instant lastFetch = lastSuccessfulFetch;
        if (lastFetch != null && now.isBefore(lastFetch.plus(cacheTtl))) {
            return true;
        }
        if (issuerUri == null || issuerUri.isBlank()) {
            return false;
        }
        try {
            discoveryClient.fetch(issuerUri);
            lastSuccessfulFetch = now;
            return true;
        } catch (OidcDiscoveryException e) {
            log.warn("OIDC issuer {} discovery unavailable, failing closed: {}", issuerUri, e.getMessage());
            return false;
        }
    }

    @Override
    public void requestEndSession(ExternalSubject externalSubject) {
        if (!adminProperties.isConfigured()) {
            log.info("Keycloak admin client not configured; treating end-session for {} as a no-op notification", externalSubject.issuer());
            return;
        }
        OidcDiscoveryDocument discovery = discoveryClient.fetch(externalSubject.issuer());
        adminClient.logoutUser(
            discovery.tokenEndpoint(), externalSubject.issuer(), adminProperties.clientId(), adminProperties.clientSecret(),
            externalSubject.subject()
        );
    }
}
