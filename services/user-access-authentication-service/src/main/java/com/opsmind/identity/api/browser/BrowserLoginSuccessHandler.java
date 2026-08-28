package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SPEC-UA-005 (04-use-cases §OIDC login: "... validate callback → establish
 * principal/session metadata"). Runs once Spring Security's own {@code
 * oauth2Login} has already validated state, PKCE, nonce, and exchanged the
 * authorization code for tokens — this class's own job starts after that:
 * link the trusted {@link UserIdentity} (SPEC-UA-001's {@code
 * ProvisionUserUseCase#link}, unspoofable because {@code issuer}/{@code
 * subject} come only from the verified {@link OidcUser}, never request
 * input) and start the real {@link UserSession} (SPEC-UA-001's {@code
 * ManageSessionUseCase#start}, which already denies a non-{@code ACTIVE}
 * identity and audits the denial — this handler does not duplicate that
 * check).
 *
 * <p>05-api-contracts: "establishes secure HttpOnly/SameSite cookie" — the
 * cookie carries only this service's own opaque {@code userSessionId},
 * never a token. Reading that cookie back on a later request (the BFF/API
 * gateway's own concern, or a future SPEC-UA-016 authentication-context
 * filter) is deliberately out of this spec's scope — 04-use-cases's own
 * "OIDC login" row ends at "establish", not "consume".
 */
@Component
public class BrowserLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String DEFAULT_ACR = "urn:mace:acr:0";

    private final ProvisionUserUseCase provisionUserUseCase;
    private final ManageSessionUseCase manageSessionUseCase;
    private final HashingPort hashingPort;
    private final BrowserLoginProperties properties;

    public BrowserLoginSuccessHandler(
        ProvisionUserUseCase provisionUserUseCase, ManageSessionUseCase manageSessionUseCase,
        HashingPort hashingPort, BrowserLoginProperties properties
    ) {
        this.provisionUserUseCase = provisionUserUseCase;
        this.manageSessionUseCase = manageSessionUseCase;
        this.hashingPort = hashingPort;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws java.io.IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String issuer = oidcUser.getIssuer().toString();
        String subject = oidcUser.getSubject();
        String correlationId = UUID.randomUUID().toString();

        // Upserts the trusted UserIdentity; ManageSessionUseCase#start below re-resolves it by (tenant, issuer, subject),
        // so the return value here is not otherwise needed.
        provisionUserUseCase.link(new LinkUserIdentityCommand(
            properties.defaultTenantId(), issuer, subject, oidcUser.getPreferredUsername(), oidcUser.getFullName(),
            oidcUser.getEmail(), IdentityType.HUMAN, correlationId
        ));

        String clientId = authentication instanceof OAuth2AuthenticationToken oauth2Token ? oauth2Token.getAuthorizedClientRegistrationId() : null;
        String acr = oidcUser.getClaimAsString("acr");
        List<String> amr = oidcUser.getClaimAsStringList("amr");
        Instant authTime = oidcUser.getAuthenticatedAt() != null ? oidcUser.getAuthenticatedAt() : Instant.now();
        String sid = oidcUser.getClaimAsString("sid");

        StartSessionCommand startCommand = new StartSessionCommand(
            properties.defaultTenantId(), issuer, subject, sid == null ? null : hashingPort.hash(sid),
            hashingPort.hash(oidcUser.getIdToken().getTokenValue()), clientId, acr == null || acr.isBlank() ? DEFAULT_ACR : acr,
            amr, authTime, null, properties.sessionTtl(), correlationId
        );

        try {
            UserSession session = manageSessionUseCase.start(startCommand);
            ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), session.userSessionId())
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(properties.sessionTtl()).build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            response.sendRedirect(properties.successRedirectUri());
        } catch (UserIdentityNotEligibleException e) {
            // Already denied and audited by ManageSessionUseCase#start itself (INV-UA-002).
            response.sendRedirect(properties.failureRedirectUri());
        }
    }
}
