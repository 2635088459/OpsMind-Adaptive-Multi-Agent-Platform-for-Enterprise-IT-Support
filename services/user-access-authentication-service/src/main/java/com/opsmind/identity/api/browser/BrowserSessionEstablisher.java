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
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The real "establish a session from a verified {@link OidcUser}" logic
 * (SPEC-UA-005 04-use-cases §OIDC login: "validate callback → establish
 * principal/session metadata"), extracted out of {@link
 * BrowserLoginSuccessHandler} so a second real login entry point —
 * {@link PasswordLoginController}'s own direct-grant flow — can produce the
 * exact same {@link UserSession} + {@code OPSMIND_SESSION} cookie without
 * duplicating this class's own careful claim handling. Both callers own
 * their OWN "what happens after" (a redirect vs. a JSON response); this
 * class's job ends at "the session now exists and the cookie is set."
 *
 * <p>Same non-goal as the handler it was extracted from: never touches
 * {@code SecurityContextHolder}/token storage — that is each caller's own
 * concern (the redirect flow's already established by {@code oauth2Login}
 * itself before this ever runs; {@link PasswordLoginController} does it
 * itself for the direct-grant case).
 */
@Component
class BrowserSessionEstablisher {

    private static final String DEFAULT_ACR = "urn:mace:acr:0";

    private final ProvisionUserUseCase provisionUserUseCase;
    private final ManageSessionUseCase manageSessionUseCase;
    private final HashingPort hashingPort;
    private final BrowserLoginProperties properties;

    BrowserSessionEstablisher(
        ProvisionUserUseCase provisionUserUseCase, ManageSessionUseCase manageSessionUseCase,
        HashingPort hashingPort, BrowserLoginProperties properties
    ) {
        this.provisionUserUseCase = provisionUserUseCase;
        this.manageSessionUseCase = manageSessionUseCase;
        this.hashingPort = hashingPort;
        this.properties = properties;
    }

    /**
     * Links the trusted {@link com.opsmind.identity.domain.user.UserIdentity},
     * starts the real {@link UserSession}, and sets the {@code OPSMIND_SESSION}
     * cookie on {@code response}. {@code issuer}/{@code subject} must come only
     * from the caller's own verified {@link OidcUser} (unspoofable), same
     * invariant {@link BrowserLoginSuccessHandler} always upheld.
     *
     * @throws UserIdentityNotEligibleException the identity is not {@code ACTIVE}
     *     (INV-UA-002) — already denied and audited by {@code ManageSessionUseCase#start}
     *     itself; no cookie is set.
     */
    UserSession establish(OidcUser oidcUser, String clientId, HttpServletResponse response) {
        String issuer = oidcUser.getIssuer().toString();
        String subject = oidcUser.getSubject();
        String correlationId = UUID.randomUUID().toString();

        // Upserts the trusted UserIdentity; ManageSessionUseCase#start below re-resolves it
        // by (tenant, issuer, subject), so the return value here is not otherwise needed.
        provisionUserUseCase.link(new LinkUserIdentityCommand(
            properties.defaultTenantId(), issuer, subject, oidcUser.getPreferredUsername(), oidcUser.getFullName(),
            oidcUser.getEmail(), IdentityType.HUMAN, correlationId
        ));

        String acr = oidcUser.getClaimAsString("acr");
        List<String> amr = oidcUser.getClaimAsStringList("amr");
        Instant authTime = oidcUser.getAuthenticatedAt() != null ? oidcUser.getAuthenticatedAt() : Instant.now();
        String sid = oidcUser.getClaimAsString("sid");

        StartSessionCommand startCommand = new StartSessionCommand(
            properties.defaultTenantId(), issuer, subject, sid == null ? null : hashingPort.hash(sid),
            hashingPort.hash(oidcUser.getIdToken().getTokenValue()), clientId, acr == null || acr.isBlank() ? DEFAULT_ACR : acr,
            amr, authTime, null, properties.sessionTtl(), correlationId
        );

        UserSession session = manageSessionUseCase.start(startCommand);
        ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), session.userSessionId())
            .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(properties.sessionTtl()).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return session;
    }
}
