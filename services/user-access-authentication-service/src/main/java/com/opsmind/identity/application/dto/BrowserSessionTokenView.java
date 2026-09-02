package com.opsmind.identity.application.dto;

/**
 * SPEC-EP-001's own BFF token-relay endpoint (05-api-contracts §Browser
 * login endpoints, extended): the real Keycloak-issued access token Spring's
 * own {@code oauth2Login} already obtained and stored (via {@code
 * OAuth2AuthorizedClientService}) as a side effect of the browser login
 * flow this service has run since SPEC-UA-005 — never a token minted by
 * this service itself, since it mints none. {@code expiresInSeconds} is
 * computed at response time from the authorized client's own {@code
 * OAuth2AccessToken#getExpiresAt()}; a caller must treat it as an estimate,
 * not a guarantee, since no clock is perfectly synchronized.
 *
 * <p>Deliberately does not attempt to auto-refresh a near-expired token —
 * this service has no {@code OAuth2AuthorizedClientManager} wired with a
 * refresh-token-capable provider yet, a known, honest gap: a caller whose
 * token has expired must send the browser through {@code
 * /oauth2/authorization/opsmind} again rather than silently receiving a
 * stale one.
 */
public record BrowserSessionTokenView(String accessToken, long expiresInSeconds) {
}
