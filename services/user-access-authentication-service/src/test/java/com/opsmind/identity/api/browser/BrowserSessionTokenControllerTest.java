package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.dto.BrowserSessionTokenView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-EP-001's own BFF token relay (see {@link
 * BrowserSessionTokenController}'s own javadoc): asserts this controller
 * never mints anything itself — it only ever surfaces whatever Spring's own
 * {@link OAuth2AuthorizedClientService} already holds for this same browser
 * session, and fails closed (401, not a stale/empty token) the moment that
 * holder has nothing real to relay.
 */
@Tag("unit")
class BrowserSessionTokenControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final OAuth2AuthorizedClientService authorizedClientService = mock(OAuth2AuthorizedClientService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BrowserSessionTokenController controller = new BrowserSessionTokenController(authorizedClientService, clock);

    private OAuth2AuthenticationToken principal() {
        DefaultOidcUser oidcUser = new DefaultOidcUser(
            List.of(), new OidcIdToken("id-token-value", NOW, NOW.plusSeconds(3600), Map.of("sub", "employee-42", "iss", "https://issuer.example"))
        );
        return new OAuth2AuthenticationToken(oidcUser, List.of(), "opsmind");
    }

    @Test
    void relaysTheRealAccessTokenAlreadyHeldByTheAuthorizedClient() {
        OAuth2AuthenticationToken authentication = principal();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "real-keycloak-access-token", NOW, NOW.plusSeconds(300)
        );
        ClientRegistration registration = ClientRegistration.withRegistrationId("opsmind")
            .clientId("user-access-authentication-service").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").authorizationUri("https://issuer.example/auth")
            .tokenUri("https://issuer.example/token").build();
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, authentication.getName(), accessToken);
        when(authorizedClientService.loadAuthorizedClient("opsmind", authentication.getName())).thenReturn(client);

        ResponseEntity<BrowserSessionTokenView> response = controller.browserToken(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accessToken()).isEqualTo("real-keycloak-access-token");
        assertThat(response.getBody().expiresInSeconds()).isBetween(290L, 300L);
    }

    @Test
    void returnsUnauthorizedWhenNoAuthorizedClientExistsForThisSession() {
        OAuth2AuthenticationToken authentication = principal();
        when(authorizedClientService.loadAuthorizedClient("opsmind", authentication.getName())).thenReturn(null);

        ResponseEntity<BrowserSessionTokenView> response = controller.browserToken(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void returnsUnauthorizedWhenTheAuthorizedClientHasNoAccessToken() {
        OAuth2AuthenticationToken authentication = principal();
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getAccessToken()).thenReturn(null);
        when(authorizedClientService.loadAuthorizedClient("opsmind", authentication.getName())).thenReturn(client);

        ResponseEntity<BrowserSessionTokenView> response = controller.browserToken(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void clampsExpiresInSecondsToZeroRatherThanGoingNegativeForAnAlreadyExpiredToken() {
        OAuth2AuthenticationToken authentication = principal();
        OAuth2AccessToken expiredToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "stale-token", NOW.minus(Duration.ofHours(1)), NOW.minusSeconds(5)
        );
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getAccessToken()).thenReturn(expiredToken);
        when(authorizedClientService.loadAuthorizedClient("opsmind", authentication.getName())).thenReturn(client);

        ResponseEntity<BrowserSessionTokenView> response = controller.browserToken(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().expiresInSeconds()).isZero();
    }
}
