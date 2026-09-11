package com.opsmind.identity.api.browser;

import com.opsmind.identity.api.error.PasswordLoginRegistrationNotAllowedException;
import com.opsmind.identity.application.dto.BrowserSessionTokenView;
import com.opsmind.identity.application.exception.InvalidPasswordGrantException;
import com.opsmind.identity.application.port.out.PasswordGrantPort;
import com.opsmind.identity.application.port.out.PasswordGrantResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The inline-form login both SPAs submit to instead of a top-level
 * navigation to Keycloak's own hosted login page — a real Resource Owner
 * Password Credentials grant, run entirely server-side so neither browser
 * ever holds a confidential client's secret (see {@link
 * PasswordGrantPort}'s own javadoc). Produces the exact same
 * result as a completed {@code oauth2Login} redirect round trip: a real
 * {@link com.opsmind.identity.domain.session.UserSession} via {@link
 * BrowserSessionEstablisher}, the {@code OPSMIND_SESSION} cookie, an {@link
 * OAuth2AuthorizedClient} recorded so {@link BrowserSessionTokenController}
 * can relay the token, and a persisted {@link SecurityContext} so a later
 * request on the same browser session is recognized as authenticated —
 * without ever redirecting.
 *
 * <p>{@code registrationId} is checked against an explicit allow-list
 * ({@link #ALLOWED_REGISTRATIONS}) — {@code opsmind-stepup} is a real
 * registration this repository resolves but must never accept a direct
 * grant (SPEC-UA-018's step-up flow has its own distinct semantics this
 * endpoint does not implement).
 */
@RestController
public class PasswordLoginController {

    private static final Set<String> ALLOWED_REGISTRATIONS = Set.of("opsmind", "support-console");
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final PasswordGrantPort tokenClient;
    private final JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final SecurityContextRepository securityContextRepository;
    private final BrowserSessionEstablisher establisher;
    private final Clock clock;

    PasswordLoginController(
        ClientRegistrationRepository clientRegistrationRepository, PasswordGrantPort tokenClient,
        JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory, OAuth2AuthorizedClientService authorizedClientService,
        SecurityContextRepository securityContextRepository, BrowserSessionEstablisher establisher, Clock clock
    ) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.tokenClient = tokenClient;
        this.idTokenDecoderFactory = idTokenDecoderFactory;
        this.authorizedClientService = authorizedClientService;
        this.securityContextRepository = securityContextRepository;
        this.establisher = establisher;
        this.clock = clock;
    }

    @PostMapping("/api/v1/session/password-login")
    public ResponseEntity<BrowserSessionTokenView> passwordLogin(
        @Valid @RequestBody PasswordLoginRequest body, HttpServletRequest request, HttpServletResponse response
    ) {
        if (!ALLOWED_REGISTRATIONS.contains(body.registrationId())) {
            throw new PasswordLoginRegistrationNotAllowedException(body.registrationId());
        }
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(body.registrationId());
        if (registration == null) {
            throw new PasswordLoginRegistrationNotAllowedException(body.registrationId());
        }

        // Server-to-server against Keycloak's own token endpoint — the confidential client's
        // secret (registration.getClientSecret()) never reaches the browser.
        PasswordGrantResult tokens = tokenClient.exchange(
            registration.getProviderDetails().getTokenUri(), registration.getClientId(), registration.getClientSecret(),
            body.username(), body.password()
        );

        // The id_token is verified the same way oauth2Login() itself would (signature,
        // issuer, expiry, and — unlike the resource-server decoder — audience == this
        // client id): OidcIdTokenDecoderFactory, the same bean SecurityConfig registers
        // for the redirect flow.
        Jwt idJwt;
        try {
            idJwt = idTokenDecoderFactory.createDecoder(registration).decode(tokens.idToken());
        } catch (JwtException | NullPointerException e) {
            throw new InvalidPasswordGrantException();
        }

        Instant issuedAt = idJwt.getIssuedAt() != null ? idJwt.getIssuedAt() : clock.instant();
        Instant idExpiresAt = idJwt.getExpiresAt() != null ? idJwt.getExpiresAt() : issuedAt.plus(Duration.ofMinutes(5));
        OidcIdToken idToken = new OidcIdToken(tokens.idToken(), issuedAt, idExpiresAt, idJwt.getClaims());
        OidcUserInfo userInfo = new OidcUserInfo(idJwt.getClaims());
        OidcUser oidcUser = new DefaultOidcUser(AUTHORITIES, idToken, userInfo, "sub");

        // Establishes the real UserSession + OPSMIND_SESSION cookie (may throw
        // UserIdentityNotEligibleException — GlobalRestExceptionHandler maps that to 403,
        // matching the redirect flow's own failure-redirect semantics).
        establisher.establish(oidcUser, body.registrationId(), response);

        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(oidcUser, AUTHORITIES, body.registrationId());

        Instant tokenIssuedAt = clock.instant();
        Instant tokenExpiresAt = tokenIssuedAt.plusSeconds(Math.max(tokens.expiresInSeconds(), 0));
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokens.accessToken(), tokenIssuedAt, tokenExpiresAt);
        OAuth2RefreshToken refreshToken = tokens.refreshToken() == null ? null : new OAuth2RefreshToken(tokens.refreshToken(), tokenIssuedAt);
        OAuth2AuthorizedClient authorizedClient = refreshToken == null
            ? new OAuth2AuthorizedClient(registration, authentication.getName(), accessToken)
            : new OAuth2AuthorizedClient(registration, authentication.getName(), accessToken, refreshToken);
        authorizedClientService.saveAuthorizedClient(authorizedClient, authentication);

        // Same effect oauth2Login()'s own AbstractAuthenticationProcessingFilter has after a
        // completed redirect round trip: the SecurityContext is both live for the REST of
        // THIS request (any @AuthenticationPrincipal downstream) and persisted into the
        // session so /api/v1/session/browser-token recognizes this caller on its next call.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        long expiresInSeconds = Math.max(0, Duration.between(clock.instant(), tokenExpiresAt).getSeconds());
        return ResponseEntity.ok(new BrowserSessionTokenView(tokens.accessToken(), expiresInSeconds));
    }
}
