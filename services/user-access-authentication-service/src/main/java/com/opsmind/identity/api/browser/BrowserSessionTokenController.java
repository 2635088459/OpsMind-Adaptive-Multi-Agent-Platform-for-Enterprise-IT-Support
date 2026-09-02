package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.dto.BrowserSessionTokenView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The BFF token-relay endpoint domain 09/10's own frontend needs (frontend
 * product vision, SPEC-EP-001): closes the exact gap {@link
 * BrowserLoginSuccessHandler}'s own javadoc names as out of SPEC-UA-005's
 * scope ("Reading that cookie back on a later request ... is deliberately
 * out of this spec's scope"). No new token is minted here — {@code
 * oauth2Login} already exchanged the authorization code for a real
 * Keycloak-issued access token during login and Spring's own default {@link
 * OAuth2AuthorizedClientService} already holds it, keyed by this same
 * browser session's {@link OAuth2AuthenticationToken}; this controller only
 * relays it to the SPA as JSON so it can be sent onward as a normal {@code
 * Authorization: Bearer} header to agent-runtime-service/
 * ticket-workflow-service, exactly like any other verified caller of this
 * platform's resource servers.
 *
 * <p>Deliberately mapped into {@link
 * com.opsmind.identity.config.SecurityConfig#browserLoginFilterChain}, not
 * the stateless resource-server chain — the whole point is reading back the
 * session {@code oauth2Login} already established, which the stateless
 * chain never creates or consults.
 */
@RestController
public class BrowserSessionTokenController {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final Clock clock;

    public BrowserSessionTokenController(OAuth2AuthorizedClientService authorizedClientService, Clock clock) {
        this.authorizedClientService = authorizedClientService;
        this.clock = clock;
    }

    @GetMapping("/api/v1/session/browser-token")
    public ResponseEntity<BrowserSessionTokenView> browserToken(OAuth2AuthenticationToken authentication) {
        // SPEC-SC-001: domain 10's own support-console logs in through a second,
        // distinct client registration ("support-console", alongside domain 09's
        // own "opsmind") — reading the registration id straight off the
        // authenticated principal (rather than a hardcoded "opsmind" constant,
        // this endpoint's own original scope) is what makes this endpoint
        // correctly relay whichever client the caller actually authenticated
        // through, without needing a second, near-duplicate controller.
        String registrationId = authentication.getAuthorizedClientRegistrationId();
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(registrationId, authentication.getName());
        OAuth2AccessToken accessToken = client == null ? null : client.getAccessToken();
        if (accessToken == null) {
            // The session survived but the authorized client did not (e.g. evicted from an
            // in-memory repository across a restart) — the honest signal is "log in again",
            // the same shape as any other unauthenticated caller of this service's own APIs.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Instant expiresAt = accessToken.getExpiresAt();
        long expiresInSeconds = expiresAt == null ? 0 : Math.max(0, Duration.between(clock.instant(), expiresAt).getSeconds());
        return ResponseEntity.ok(new BrowserSessionTokenView(accessToken.getTokenValue(), expiresInSeconds));
    }
}
