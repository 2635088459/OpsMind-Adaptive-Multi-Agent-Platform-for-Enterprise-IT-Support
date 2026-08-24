/**
 * 05-api-contracts §Browser login endpoints ({@code GET /oauth2/authorization/{provider}},
 * {@code GET /login/oauth2/code/{provider}}). Deliberately empty in
 * SPEC-UA-001: real Authorization Code + PKCE login/callback requires
 * {@code spring-boot-starter-oauth2-client} and a live Keycloak realm,
 * which are SPEC-UA-004's (OIDC Provider Discovery And Configuration) and
 * SPEC-UA-005's (Authorization Code PKCE Login Callback) job — see the
 * "Excludes custom ... OIDC protocol implementation" line in this spec's
 * own scope.
 */
package com.opsmind.identity.api.browser;
