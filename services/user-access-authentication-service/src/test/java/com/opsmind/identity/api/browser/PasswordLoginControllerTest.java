package com.opsmind.identity.api.browser;

import com.opsmind.identity.api.error.PasswordLoginRegistrationNotAllowedException;
import com.opsmind.identity.application.dto.BrowserSessionTokenView;
import com.opsmind.identity.application.exception.InvalidPasswordGrantException;
import com.opsmind.identity.application.port.out.PasswordGrantPort;
import com.opsmind.identity.application.port.out.PasswordGrantResult;
import com.opsmind.identity.domain.session.AuthenticationAssurance;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-EP-001/SPEC-SC-001 follow-up: {@link PasswordLoginController} is the
 * inline-form login both SPAs submit instead of a top-level navigation to
 * Keycloak's own hosted login page. These tests mock at the port boundary
 * ({@link PasswordGrantPort}, same posture the rest of this codebase's own
 * infra-wrapping classes are tested at, not the raw HTTP call) and assert
 * this class's own orchestration: registration allow-listing, id_token
 * verification, delegating to {@link BrowserSessionEstablisher}, and
 * registering the authorized client + security context so a later call to
 * {@code /api/v1/session/browser-token} recognizes the caller.
 */
@Tag("unit")
class PasswordLoginControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ClientRegistrationRepository clientRegistrationRepository = mock(ClientRegistrationRepository.class);
    private final PasswordGrantPort tokenClient = mock(PasswordGrantPort.class);
    @SuppressWarnings("unchecked")
    private final JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory = mock(JwtDecoderFactory.class);
    private final JwtDecoder idTokenDecoder = mock(JwtDecoder.class);
    private final OAuth2AuthorizedClientService authorizedClientService = mock(OAuth2AuthorizedClientService.class);
    private final SecurityContextRepository securityContextRepository = mock(SecurityContextRepository.class);
    private final BrowserSessionEstablisher establisher = mock(BrowserSessionEstablisher.class);

    private final PasswordLoginController controller = new PasswordLoginController(
        clientRegistrationRepository, tokenClient, idTokenDecoderFactory, authorizedClientService,
        securityContextRepository, establisher, CLOCK
    );

    private ClientRegistration opsmindRegistration() {
        return ClientRegistration.withRegistrationId("opsmind")
            .clientId("user-access-authentication-service").clientSecret("secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("http://keycloak:8080/realms/opsmind/protocol/openid-connect/auth")
            .tokenUri("http://keycloak:8080/realms/opsmind/protocol/openid-connect/token")
            .build();
    }

    private Jwt aValidIdJwt() {
        return Jwt.withTokenValue("raw-id-token")
            .header("alg", "RS256")
            .issuedAt(NOW).expiresAt(NOW.plusSeconds(300))
            .claim("iss", "https://idp.example/realms/opsmind").claim("sub", "sub-1")
            .claim("preferred_username", "alice").claim("email", "alice@example.com")
            .build();
    }

    private UserSession aSession() {
        return UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-x"), new ExternalSubject("https://idp.example/realms/opsmind", "sub-1"),
            "idp-hash", "token-hash", "opsmind", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW), null, NOW, NOW.plusSeconds(7200)
        );
    }

    @Test
    void rejectsARegistrationIdOutsideTheAllowList() {
        PasswordLoginRequest body = new PasswordLoginRequest("opsmind-stepup", "alice", "pw");

        assertThatThrownBy(() -> controller.passwordLogin(body, new MockHttpServletRequest(), new MockHttpServletResponse()))
            .isInstanceOf(PasswordLoginRegistrationNotAllowedException.class);
    }

    @Test
    void rejectsARegistrationIdTheRepositoryDoesNotResolve() {
        when(clientRegistrationRepository.findByRegistrationId("support-console")).thenReturn(null);
        PasswordLoginRequest body = new PasswordLoginRequest("support-console", "alice", "pw");

        assertThatThrownBy(() -> controller.passwordLogin(body, new MockHttpServletRequest(), new MockHttpServletResponse()))
            .isInstanceOf(PasswordLoginRegistrationNotAllowedException.class);
    }

    @Test
    void exchangesEstablishesASessionAndRegistersTheAuthorizedClientOnSuccess() {
        ClientRegistration registration = opsmindRegistration();
        when(clientRegistrationRepository.findByRegistrationId("opsmind")).thenReturn(registration);
        when(tokenClient.exchange(registration.getProviderDetails().getTokenUri(), "user-access-authentication-service", "secret", "alice", "s3cret"))
            .thenReturn(new PasswordGrantResult("real-access-token", "real-refresh-token", "raw-id-token", 300));
        when(idTokenDecoderFactory.createDecoder(registration)).thenReturn(idTokenDecoder);
        when(idTokenDecoder.decode("raw-id-token")).thenReturn(aValidIdJwt());
        when(establisher.establish(any(), eq("opsmind"), any())).thenReturn(aSession());

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<BrowserSessionTokenView> result = controller.passwordLogin(
            new PasswordLoginRequest("opsmind", "alice", "s3cret"), request, response
        );

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().accessToken()).isEqualTo("real-access-token");
        assertThat(result.getBody().expiresInSeconds()).isEqualTo(300);

        ArgumentCaptor<OidcUser> oidcUserCaptor = ArgumentCaptor.forClass(OidcUser.class);
        verify(establisher).establish(oidcUserCaptor.capture(), eq("opsmind"), eq(response));
        assertThat(oidcUserCaptor.getValue().getSubject()).isEqualTo("sub-1");
        assertThat(oidcUserCaptor.getValue().getPreferredUsername()).isEqualTo("alice");

        ArgumentCaptor<OAuth2AuthorizedClient> authorizedClientCaptor = ArgumentCaptor.forClass(OAuth2AuthorizedClient.class);
        ArgumentCaptor<OAuth2AuthenticationToken> authenticationCaptor = ArgumentCaptor.forClass(OAuth2AuthenticationToken.class);
        verify(authorizedClientService).saveAuthorizedClient(authorizedClientCaptor.capture(), authenticationCaptor.capture());
        assertThat(authorizedClientCaptor.getValue().getAccessToken().getTokenValue()).isEqualTo("real-access-token");
        assertThat(authorizedClientCaptor.getValue().getRefreshToken()).isNotNull();
        assertThat(authorizedClientCaptor.getValue().getRefreshToken().getTokenValue()).isEqualTo("real-refresh-token");
        assertThat(authenticationCaptor.getValue().getAuthorizedClientRegistrationId()).isEqualTo("opsmind");
        assertThat(authenticationCaptor.getValue().getName()).isEqualTo("sub-1");

        verify(securityContextRepository).saveContext(any(), eq(request), eq(response));
    }

    @Test
    void treatsAMissingOrUnverifiableIdTokenAsInvalidCredentials() {
        ClientRegistration registration = opsmindRegistration();
        when(clientRegistrationRepository.findByRegistrationId("opsmind")).thenReturn(registration);
        when(tokenClient.exchange(any(), any(), any(), any(), any()))
            .thenReturn(new PasswordGrantResult("token", null, "raw-id-token", 300));
        when(idTokenDecoderFactory.createDecoder(registration)).thenReturn(idTokenDecoder);
        when(idTokenDecoder.decode("raw-id-token")).thenThrow(new JwtException("bad signature"));

        assertThatThrownBy(() -> controller.passwordLogin(
            new PasswordLoginRequest("opsmind", "alice", "wrong"), new MockHttpServletRequest(), new MockHttpServletResponse()
        )).isInstanceOf(InvalidPasswordGrantException.class);

        verify(establisher, never()).establish(any(), any(), any());
        verify(authorizedClientService, never()).saveAuthorizedClient(any(), any());
    }
}
