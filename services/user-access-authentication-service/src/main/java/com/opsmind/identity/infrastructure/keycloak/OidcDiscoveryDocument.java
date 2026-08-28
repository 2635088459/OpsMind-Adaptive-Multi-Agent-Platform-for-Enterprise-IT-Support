package com.opsmind.identity.infrastructure.keycloak;

import java.util.List;

/**
 * The subset of an OIDC provider's {@code .well-known/openid-configuration}
 * document domain 01 actually consumes (SPEC-UA-004; 11-security: "Each
 * issuer has fixed discovery URL, allowed algorithms, audiences, and token
 * types"). Deliberately not the full discovery schema — only the fields
 * {@link OidcDiscoveryClient}'s caller needs.
 */
public record OidcDiscoveryDocument(
    String issuer,
    String authorizationEndpoint,
    String tokenEndpoint,
    String jwksUri,
    String endSessionEndpoint,
    List<String> idTokenSigningAlgValuesSupported
) {
}
