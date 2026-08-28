package com.opsmind.identity.infrastructure.keycloak;

import com.opsmind.identity.support.StubHttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-UA-004: fetches and parses a real {@code .well-known/openid-configuration} document over real HTTP. */
@Tag("unit")
class OidcDiscoveryClientTest {

    private final OidcDiscoveryClient client = new OidcDiscoveryClient(RestClient.create());

    @Test
    void fetchParsesTheDiscoveryDocument() {
        try (StubHttpServer server = StubHttpServer.create()) {
            String body = """
                {
                  "issuer": "%1$s",
                  "authorization_endpoint": "%1$s/protocol/openid-connect/auth",
                  "token_endpoint": "%1$s/protocol/openid-connect/token",
                  "jwks_uri": "%1$s/protocol/openid-connect/certs",
                  "end_session_endpoint": "%1$s/protocol/openid-connect/logout",
                  "id_token_signing_alg_values_supported": ["RS256"]
                }
                """.formatted(server.baseUrl());
            server.route("/.well-known/openid-configuration", () -> body);
            server.start();

            OidcDiscoveryDocument discovery = client.fetch(server.baseUrl());

            assertThat(discovery.issuer()).isEqualTo(server.baseUrl());
            assertThat(discovery.jwksUri()).endsWith("/certs");
            assertThat(discovery.endSessionEndpoint()).endsWith("/logout");
            assertThat(discovery.idTokenSigningAlgValuesSupported()).containsExactly("RS256");
        }
    }

    @Test
    void fetchToleratesATrailingSlashOnTheIssuer() {
        try (StubHttpServer server = StubHttpServer.create()) {
            String body = """
                {"issuer": "x", "jwks_uri": "%s/certs"}
                """.formatted(server.baseUrl());
            server.route("/.well-known/openid-configuration", () -> body);
            server.start();

            OidcDiscoveryDocument discovery = client.fetch(server.baseUrl() + "/");
            assertThat(discovery.jwksUri()).isEqualTo(server.baseUrl() + "/certs");
        }
    }

    /** SPEC-UA-034 (11-security: threat modeling names "JWKS poisoning" explicitly). */
    @Test
    void fetchFailsClosedWhenJwksUriIsOnADifferentOriginThanTheIssuer() {
        try (StubHttpServer server = StubHttpServer.create()) {
            String body = """
                {"issuer": "x", "jwks_uri": "http://attacker.invalid/certs"}
                """;
            server.route("/.well-known/openid-configuration", () -> body);
            server.start();

            assertThatThrownBy(() -> client.fetch(server.baseUrl())).isInstanceOf(OidcDiscoveryException.class);
        }
    }

    @Test
    void fetchFailsClosedWhenJwksUriIsMissing() {
        String body = """
            {"issuer": "x"}
            """;
        try (StubHttpServer server = StubHttpServer.startWithJsonRoutes(Map.of("/.well-known/openid-configuration", body))) {
            assertThatThrownBy(() -> client.fetch(server.baseUrl())).isInstanceOf(OidcDiscoveryException.class);
        }
    }

    @Test
    void fetchFailsClosedWhenUnreachable() {
        assertThatThrownBy(() -> client.fetch("http://localhost:1"))
            .isInstanceOf(OidcDiscoveryException.class);
    }

    @Test
    void fetchFailsClosedOnMalformedJson() {
        try (StubHttpServer server = StubHttpServer.startWithJsonRoutes(Map.of("/.well-known/openid-configuration", "not json"))) {
            assertThatThrownBy(() -> client.fetch(server.baseUrl())).isInstanceOf(OidcDiscoveryException.class);
        }
    }
}
