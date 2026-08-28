package com.opsmind.identity.infrastructure.keycloak;

import com.opsmind.identity.config.KeycloakAdminProperties;
import com.opsmind.identity.config.OidcIssuerProperties;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.support.StubHttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SPEC-UA-004: {@link KeycloakOidcProviderAdapter#isAvailable} must reflect
 * a real, TTL-cached discovery signal (09-concurrency-and-idempotency:
 * "Sensitive operations deny when ... IdP trust fails") — never a hardcoded
 * value in either direction.
 *
 * <p>SPEC-UA-009: {@link KeycloakOidcProviderAdapter#requestEndSession} is a
 * real Keycloak Admin API call once {@link KeycloakAdminProperties} is
 * configured, and a graceful no-op (never a call at all) otherwise.
 */
@Tag("unit")
class KeycloakOidcProviderAdapterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final OidcDiscoveryDocument DOCUMENT = new OidcDiscoveryDocument("iss", "authz", "token", "jwks", "logout", List.of("RS256"));

    private final MutableClock clock = new MutableClock(NOW);
    private final OidcIssuerProperties properties = new OidcIssuerProperties(List.of("RS256"), List.of(), Duration.ofMinutes(15), null, null);
    private final KeycloakAdminProperties unconfiguredAdminProperties = new KeycloakAdminProperties(null, null);

    @Test
    void isAvailableIsFalseWhenIssuerUriIsUnset() {
        OidcDiscoveryClient discoveryClient = mock(OidcDiscoveryClient.class);
        KeycloakOidcProviderAdapter adapter = new KeycloakOidcProviderAdapter(
            discoveryClient, mock(KeycloakAdminClient.class), "", properties, unconfiguredAdminProperties, clock
        );

        assertThat(adapter.isAvailable()).isFalse();
    }

    @Test
    void isAvailableIsTrueAfterASuccessfulFetchAndCachedWithinTheTtl() {
        OidcDiscoveryClient discoveryClient = mock(OidcDiscoveryClient.class);
        when(discoveryClient.fetch(anyString())).thenReturn(DOCUMENT);
        KeycloakOidcProviderAdapter adapter = new KeycloakOidcProviderAdapter(
            discoveryClient, mock(KeycloakAdminClient.class), "https://idp.example/realms/opsmind", properties, unconfiguredAdminProperties, clock
        );

        assertThat(adapter.isAvailable()).isTrue();
        clock.advanceTo(NOW.plusSeconds(60));
        assertThat(adapter.isAvailable()).isTrue();

        verify(discoveryClient, times(1)).fetch(anyString());
    }

    @Test
    void isAvailableRefetchesAndFailsClosedOnceTheCacheGoesStale() {
        OidcDiscoveryClient discoveryClient = mock(OidcDiscoveryClient.class);
        when(discoveryClient.fetch(anyString())).thenReturn(DOCUMENT).thenThrow(new OidcDiscoveryException("unreachable"));
        KeycloakOidcProviderAdapter adapter = new KeycloakOidcProviderAdapter(
            discoveryClient, mock(KeycloakAdminClient.class), "https://idp.example/realms/opsmind", properties, unconfiguredAdminProperties, clock
        );

        assertThat(adapter.isAvailable()).isTrue();
        clock.advanceTo(NOW.plus(properties.discoveryCacheTtl()).plusSeconds(1));
        assertThat(adapter.isAvailable()).isFalse();

        verify(discoveryClient, times(2)).fetch(anyString());
    }

    @Test
    void requestEndSessionIsAGracefulNoOpWhenTheAdminClientIsNotConfigured() {
        OidcDiscoveryClient discoveryClient = mock(OidcDiscoveryClient.class);
        KeycloakAdminClient adminClient = mock(KeycloakAdminClient.class);
        KeycloakOidcProviderAdapter adapter = new KeycloakOidcProviderAdapter(
            discoveryClient, adminClient, "https://idp.example/realms/opsmind", properties, unconfiguredAdminProperties, clock
        );

        adapter.requestEndSession(new ExternalSubject("https://idp.example/realms/opsmind", "sub-1"));

        verifyNoInteractions(adminClient);
        verifyNoInteractions(discoveryClient);
    }

    @Test
    void requestEndSessionCallsTheRealKeycloakAdminApiWhenConfigured() {
        try (StubHttpServer stub = StubHttpServer.create()) {
            AtomicBoolean logoutCalled = new AtomicBoolean(false);
            stub.route("/token", () -> "{\"access_token\":\"admin-token\"}");
            stub.route("/admin/realms/opsmind/users/sub-1/logout", () -> {
                logoutCalled.set(true);
                return "{}";
            });
            stub.start();

            String issuer = stub.baseUrl() + "/realms/opsmind";
            OidcDiscoveryDocument discovery = new OidcDiscoveryDocument(issuer, "authz", stub.baseUrl() + "/token", "jwks", "logout", List.of("RS256"));
            OidcDiscoveryClient discoveryClient = mock(OidcDiscoveryClient.class);
            when(discoveryClient.fetch(issuer)).thenReturn(discovery);
            KeycloakAdminClient adminClient = new KeycloakAdminClient();
            KeycloakAdminProperties adminProperties = new KeycloakAdminProperties("admin-cli", "secret");
            KeycloakOidcProviderAdapter adapter = new KeycloakOidcProviderAdapter(discoveryClient, adminClient, issuer, properties, adminProperties, clock);

            adapter.requestEndSession(new ExternalSubject(issuer, "sub-1"));

            assertThat(logoutCalled.get()).isTrue();
        }
    }

    @Test
    void adminUsersLogoutUriIsDerivedFromTheIssuersRealmsMarker() {
        assertThat(KeycloakAdminClient.adminUsersLogoutUri("https://idp.example/realms/opsmind", "sub-1"))
            .isEqualTo("https://idp.example/admin/realms/opsmind/users/sub-1/logout");
    }

    @Test
    void adminUsersLogoutUriRejectsAnIssuerWithoutARealmsMarker() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> KeycloakAdminClient.adminUsersLogoutUri("https://idp.example", "sub-1"))
            .isInstanceOf(OidcDiscoveryException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceTo(Instant instant) {
            this.instant = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
