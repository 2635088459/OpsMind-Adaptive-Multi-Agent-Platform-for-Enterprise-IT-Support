package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.session.AuthenticationAssurance;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-UA-005: with the actual "link/start/cookie" logic extracted into
 * {@link BrowserSessionEstablisher} (its own dedicated test), this class only
 * covers what THIS handler alone still owns — picking a redirect destination
 * by registration id, and the not-eligible failure path.
 */
@Tag("unit")
class BrowserLoginSuccessHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final BrowserSessionEstablisher establisher = mock(BrowserSessionEstablisher.class);
    private final BrowserLoginProperties properties = new BrowserLoginProperties("tenant-x", Duration.ofHours(2), "MY_COOKIE", "/home", "/login?error", "/support-console-home");
    private final BrowserLoginSuccessHandler handler = new BrowserLoginSuccessHandler(establisher, properties);

    private OAuth2AuthenticationToken authenticationFor(String registrationId) {
        OidcIdToken idToken = new OidcIdToken("raw-id-token", NOW, NOW.plusSeconds(300),
            Map.of("iss", "https://idp.example/realms/opsmind", "sub", "sub-1"));
        OidcUserInfo userInfo = new OidcUserInfo(Map.of("sub", "sub-1", "preferred_username", "alice"));
        DefaultOidcUser oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, userInfo, "sub");
        return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), registrationId);
    }

    private UserSession aSession() {
        return UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-x"), new ExternalSubject("https://idp.example/realms/opsmind", "sub-1"),
            "idp-hash", "token-hash", "opsmind", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW), null, NOW, NOW.plusSeconds(7200)
        );
    }

    @Test
    void redirectsToTheEmployeePortalTargetForTheOpsmindRegistration() throws Exception {
        when(establisher.establish(any(), eq("opsmind"), any())).thenReturn(aSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authenticationFor("opsmind"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/home");
        verify(establisher).establish(any(), eq("opsmind"), eq(response));
    }

    @Test
    void redirectsToTheSupportConsoleTargetWhenLoggedInThroughItsOwnRegistration() throws Exception {
        when(establisher.establish(any(), eq("support-console"), any())).thenReturn(aSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authenticationFor("support-console"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/support-console-home");
    }

    @Test
    void redirectsToTheFailureUriWhenTheUserIdentityIsNotEligible() throws Exception {
        when(establisher.establish(any(), any(), any())).thenThrow(new UserIdentityNotEligibleException("user-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authenticationFor("opsmind"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }
}
