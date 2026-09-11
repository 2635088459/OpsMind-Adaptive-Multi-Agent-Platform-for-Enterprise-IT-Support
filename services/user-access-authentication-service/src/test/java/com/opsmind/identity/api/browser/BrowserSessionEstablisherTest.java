package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.session.AuthenticationAssurance;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.infrastructure.hashing.Sha256HashingAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-UA-005: this is the real "link identity / start session / set cookie"
 * logic both real login entry points — {@link BrowserLoginSuccessHandler}
 * (redirect flow) and {@link PasswordLoginController} (direct-grant flow)
 * — share. Constructs the {@code OidcUser} directly (no HTTP, no Keycloak)
 * and asserts exactly what gets linked/started and the response cookie.
 */
@Tag("unit")
class BrowserSessionEstablisherTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ProvisionUserUseCase provisionUserUseCase = mock(ProvisionUserUseCase.class);
    private final ManageSessionUseCase manageSessionUseCase = mock(ManageSessionUseCase.class);
    private final HashingPort hashingPort = new Sha256HashingAdapter();
    private final BrowserLoginProperties properties = new BrowserLoginProperties("tenant-x", Duration.ofHours(2), "MY_COOKIE", "/home", "/login?error", "/support-console-home");
    private final BrowserSessionEstablisher establisher = new BrowserSessionEstablisher(provisionUserUseCase, manageSessionUseCase, hashingPort, properties);

    private OidcUser oidcUserWithClaims(Map<String, Object> extraClaims) {
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("iss", "https://idp.example/realms/opsmind");
        claims.put("sub", "sub-1");
        claims.putAll(extraClaims);
        OidcIdToken idToken = new OidcIdToken("raw-id-token", NOW, NOW.plusSeconds(300), claims);
        OidcUserInfo userInfo = new OidcUserInfo(Map.of(
            "sub", "sub-1", "preferred_username", "alice", "name", "Alice", "email", "alice@example.com"
        ));
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, userInfo, "sub");
    }

    private UserSession aSession() {
        return UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-x"), new ExternalSubject("https://idp.example/realms/opsmind", "sub-1"),
            "idp-hash", "token-hash", "opsmind", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW), null, NOW, NOW.plusSeconds(7200)
        );
    }

    @Test
    void linksTheIdentityStartsTheSessionAndSetsTheCookie() {
        UserIdentity linkedUser = UserIdentity.link(
            UUID.randomUUID().toString(), new TenantId("tenant-x"), new ExternalSubject("https://idp.example/realms/opsmind", "sub-1"),
            "alice", "Alice", "alice@example.com", IdentityType.HUMAN, NOW
        );
        when(provisionUserUseCase.link(any())).thenReturn(linkedUser);
        UserSession startedSession = aSession();
        when(manageSessionUseCase.start(any())).thenReturn(startedSession);

        OidcUser oidcUser = oidcUserWithClaims(Map.of(
            "acr", "urn:mace:acr:silver", "amr", List.of("pwd", "otp"), "sid", "idp-session-1", "auth_time", Date.from(NOW)
        ));
        MockHttpServletResponse response = new MockHttpServletResponse();

        UserSession result = establisher.establish(oidcUser, "opsmind", response);

        assertThat(result).isSameAs(startedSession);

        var linkCommand = captureLinkCommand();
        assertThat(linkCommand.tenantId()).isEqualTo("tenant-x");
        assertThat(linkCommand.issuer()).isEqualTo("https://idp.example/realms/opsmind");
        assertThat(linkCommand.subject()).isEqualTo("sub-1");
        assertThat(linkCommand.username()).isEqualTo("alice");
        assertThat(linkCommand.identityType()).isEqualTo(IdentityType.HUMAN);

        var startCommand = captureStartCommand();
        assertThat(startCommand.tenantId()).isEqualTo("tenant-x");
        assertThat(startCommand.acr()).isEqualTo("urn:mace:acr:silver");
        assertThat(startCommand.amr()).containsExactly("pwd", "otp");
        assertThat(startCommand.idpSessionIdHash()).isEqualTo(hashingPort.hash("idp-session-1"));
        assertThat(startCommand.tokenIdHash()).isEqualTo(hashingPort.hash("raw-id-token"));
        assertThat(startCommand.clientId()).isEqualTo("opsmind");
        assertThat(startCommand.ttl()).isEqualTo(Duration.ofHours(2));

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("MY_COOKIE=" + startedSession.userSessionId());
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("Secure");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void fallsBackToTheDefaultAcrWhenTheIdTokenHasNoAcrClaim() {
        when(provisionUserUseCase.link(any())).thenReturn(UserIdentity.link(
            UUID.randomUUID().toString(), new TenantId("tenant-x"), new ExternalSubject("https://idp.example/realms/opsmind", "sub-1"),
            null, null, null, IdentityType.HUMAN, NOW
        ));
        when(manageSessionUseCase.start(any())).thenReturn(aSession());

        establisher.establish(oidcUserWithClaims(Map.of()), "opsmind", new MockHttpServletResponse());

        assertThat(captureStartCommand().acr()).isEqualTo("urn:mace:acr:0");
    }

    @Test
    void propagatesNotEligibleWithoutSettingACookie() {
        when(provisionUserUseCase.link(any())).thenReturn(UserIdentity.link(
            UUID.randomUUID().toString(), new TenantId("tenant-x"), new ExternalSubject("https://idp.example/realms/opsmind", "sub-1"),
            null, null, null, IdentityType.HUMAN, NOW
        ));
        when(manageSessionUseCase.start(any())).thenThrow(new UserIdentityNotEligibleException("user-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> establisher.establish(oidcUserWithClaims(Map.of()), "opsmind", response))
            .isInstanceOf(UserIdentityNotEligibleException.class);

        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    private LinkUserIdentityCommand captureLinkCommand() {
        org.mockito.ArgumentCaptor<LinkUserIdentityCommand> captor = org.mockito.ArgumentCaptor.forClass(LinkUserIdentityCommand.class);
        verify(provisionUserUseCase).link(captor.capture());
        return captor.getValue();
    }

    private StartSessionCommand captureStartCommand() {
        org.mockito.ArgumentCaptor<StartSessionCommand> captor = org.mockito.ArgumentCaptor.forClass(StartSessionCommand.class);
        verify(manageSessionUseCase).start(captor.capture());
        return captor.getValue();
    }
}
