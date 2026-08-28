package com.opsmind.identity.infrastructure.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * SPEC-UA-004: fetches and parses one issuer's real {@code
 * .well-known/openid-configuration} document — the concrete "fixed
 * discovery URL" 11-security names. A standalone {@link RestClient}, not
 * the web layer's autoconfigured one, so this stays independent of any
 * inbound HTTP framework wiring and is trivially unit-testable against an
 * embedded HTTP server.
 *
 * <p>SPEC-UA-034 (11-security: threat modeling names "JWKS poisoning"
 * explicitly): {@link #fetch} also rejects a discovery document whose own
 * {@code jwks_uri} does not share the requested issuer's own origin
 * (scheme+host+port) — a compromised or misconfigured discovery endpoint
 * cannot redirect key material fetches to an attacker-controlled host.
 */
@Component
public class OidcDiscoveryClient {

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OidcDiscoveryClient() {
        this(RestClient.create());
    }

    OidcDiscoveryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @throws OidcDiscoveryException if the endpoint is unreachable, returns
     *     a non-2xx status, or the body cannot be parsed as the expected
     *     discovery document — the caller's own fail-closed handling
     *     (INV-UA-002) depends on this never returning a partial/best-guess
     *     document.
     */
    public OidcDiscoveryDocument fetch(String issuerUri) {
        String normalizedIssuer = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        try {
            String body = restClient.get()
                .uri(normalizedIssuer + DISCOVERY_PATH)
                .retrieve()
                .body(String.class);
            OidcDiscoveryDocument document = parse(body);
            requireSameOrigin(normalizedIssuer, document.jwksUri());
            return document;
        } catch (OidcDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcDiscoveryException("failed to fetch OIDC discovery document from " + normalizedIssuer, e);
        }
    }

    /** SPEC-UA-034: rejects a {@code jwks_uri} on a different scheme+host+port than the issuer it was fetched from — JWKS-poisoning defense. */
    private void requireSameOrigin(String issuerUri, String jwksUri) {
        URI issuer = URI.create(issuerUri);
        URI jwks = URI.create(jwksUri);
        boolean sameOrigin = java.util.Objects.equals(issuer.getScheme(), jwks.getScheme())
            && java.util.Objects.equals(issuer.getHost(), jwks.getHost())
            && effectivePort(issuer) == effectivePort(jwks);
        if (!sameOrigin) {
            throw new OidcDiscoveryException("jwks_uri " + jwksUri + " is not on the same origin as issuer " + issuerUri + " — refusing (JWKS-poisoning defense)");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private OidcDiscoveryDocument parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<String> algorithms = new ArrayList<>();
            if (root.has("id_token_signing_alg_values_supported")) {
                root.get("id_token_signing_alg_values_supported").forEach(node -> algorithms.add(node.asText()));
            }
            return new OidcDiscoveryDocument(
                textOrNull(root, "issuer"), textOrNull(root, "authorization_endpoint"), textOrNull(root, "token_endpoint"),
                requireText(root, "jwks_uri"), textOrNull(root, "end_session_endpoint"), List.copyOf(algorithms)
            );
        } catch (OidcDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcDiscoveryException("malformed OIDC discovery document", e);
        }
    }

    private String textOrNull(JsonNode root, String field) {
        return root.has(field) ? root.get(field).asText() : null;
    }

    private String requireText(JsonNode root, String field) {
        String value = textOrNull(root, field);
        if (value == null || value.isBlank()) {
            throw new OidcDiscoveryException("OIDC discovery document is missing required field '" + field + "'");
        }
        return value;
    }
}
