package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.command.LogoutCommand;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.config.BrowserLoginProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real "sign out" a browser session was missing — see the controller's
 * own javadoc. Asserts the three things that actually matter: the domain
 * session is revoked (best-effort), the authorized client is forgotten so
 * {@link BrowserSessionTokenController} cannot keep relaying it, and the
 * {@code OPSMIND_SESSION} cookie comes back expired.
 */
@Tag("unit")
class BrowserLogoutControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ManageSessionUseCase manageSessionUseCase = mock(ManageSessionUseCase.class);
    private final HashingPort hashingPort = mock(HashingPort.class);
    private final BrowserLoginProperties properties = new BrowserLoginProperties("tenant-x", Duration.ofHours(2), "OPSMIND_SESSION", "/home", "/login?error", "/support-console-home");
    private final OAuth2AuthorizedClientService authorizedClientService = mock(OAuth2AuthorizedClientService.class);
    private final BrowserLogoutController controller = new BrowserLogoutController(manageSessionUseCase, hashingPort, properties, authorizedClientService);

    private OAuth2AuthenticationToken anAuthentication() {
        OidcIdToken idToken = new OidcIdToken("raw-id-token", NOW, NOW.plusSeconds(300), Map.of(
            "iss", "https://idp.example/realms/opsmind", "sub", "sub-1", "sid", "idp-session-1"
        ));
        OidcUserInfo userInfo = new OidcUserInfo(Map.of("sub", "sub-1", "preferred_username", "alice"));
        DefaultOidcUser oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, userInfo, "sub");
        return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "opsmind");
    }

    @Test
    void revokesTheDomainSessionForgetsTheAuthorizedClientAndExpiresTheCookie() {
        when(hashingPort.hash("idp-session-1")).thenReturn("hashed-sid");
        OAuth2AuthenticationToken authentication = anAuthentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Void> result = controller.logout(authentication, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var captor = org.mockito.ArgumentCaptor.forClass(LogoutCommand.class);
        verify(manageSessionUseCase).logout(captor.capture());
        assertThat(captor.getValue().issuer()).isEqualTo("https://idp.example/realms/opsmind");
        assertThat(captor.getValue().subject()).isEqualTo("sub-1");
        assertThat(captor.getValue().idpSessionIdHash()).isEqualTo("hashed-sid");

        verify(authorizedClientService).removeAuthorizedClient("opsmind", "sub-1");

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("OPSMIND_SESSION=");
        assertThat(setCookie).containsIgnoringCase("Max-Age=0");

        assertThat(request.getSession(false)).isNull(); // invalidated
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aFailedDomainRevokeNeverBlocksClearingTheBrowserSession() {
        when(hashingPort.hash(any())).thenReturn("hashed-sid");
        doThrow(new RuntimeException("db down")).when(manageSessionUseCase).logout(any());
        OAuth2AuthenticationToken authentication = anAuthentication();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Void> result = controller.logout(authentication, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void anUnauthenticatedCallStillClearsWhateverSessionExistsWithoutThrowing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Void> result = controller.logout(null, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(manageSessionUseCase, never()).logout(any());
        verify(authorizedClientService, never()).removeAuthorizedClient(any(), any());
    }
}
