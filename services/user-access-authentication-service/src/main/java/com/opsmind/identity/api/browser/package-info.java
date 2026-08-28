/**
 * 05-api-contracts §Browser login endpoints ({@code GET /oauth2/authorization/{provider}},
 * {@code GET /login/oauth2/code/{provider}}) — both provided by Spring
 * Security's own {@code oauth2Login} filter chain ({@code
 * config.SecurityConfig#browserLoginFilterChain}, SPEC-UA-005). This
 * package holds only what runs after those framework filters already
 * validated state/PKCE/nonce and exchanged the code: {@link
 * BrowserLoginSuccessHandler} (establish principal/session) and {@link
 * BrowserLoginFailureHandler} (reject and audit).
 */
package com.opsmind.identity.api.browser;
