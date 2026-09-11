package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.command.LogoutCommand;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.config.BrowserLoginProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The real "sign out" a browser session was always missing: {@code
 * BrowserLoginSuccessHandler}'s own javadoc names reading the {@code
 * OPSMIND_SESSION} cookie back as future-scope, but nothing ever ended a
 * browser session either — the only existing logout
 * ({@code SessionController#logout}, SPEC-UA-009 {@code
 * /internal/identity/v1/sessions/logout}) needs a Bearer JWT on the
 * stateless resource-server chain, not the HttpOnly cookie a browser holds,
 * so an SPA could never call it to switch accounts in the same tab short of
 * clearing cookies by hand.
 *
 * <p>Ends the session the SAME way it was started, symmetrically: revokes
 * the real domain {@link com.opsmind.identity.domain.session.UserSession}
 * (best-effort, same {@link ManageSessionUseCase#logout} SPEC-UA-009's own
 * endpoint calls — a failure here never blocks the browser from clearing
 * its own session), forgets the {@link
 * org.springframework.security.oauth2.client.OAuth2AuthorizedClient} {@link
 * BrowserSessionTokenController} would otherwise keep relaying, invalidates
 * the underlying `HttpSession` (which is what actually gates every
 * `.authenticated()` request on this chain — {@code OPSMIND_SESSION} itself
 * is never read back by anything), and expires both cookies in the
 * response.
 */
@RestController
public class BrowserLogoutController {

    private static final Logger log = LoggerFactory.getLogger(BrowserLogoutController.class);

    private final ManageSessionUseCase manageSessionUseCase;
    private final HashingPort hashingPort;
    private final BrowserLoginProperties properties;
    private final OAuth2AuthorizedClientService authorizedClientService;

    BrowserLogoutController(
        ManageSessionUseCase manageSessionUseCase, HashingPort hashingPort,
        BrowserLoginProperties properties, OAuth2AuthorizedClientService authorizedClientService
    ) {
        this.manageSessionUseCase = manageSessionUseCase;
        this.hashingPort = hashingPort;
        this.properties = properties;
        this.authorizedClientService = authorizedClientService;
    }

    @PostMapping("/api/v1/session/logout")
    public ResponseEntity<Void> logout(OAuth2AuthenticationToken authentication, HttpServletRequest request, HttpServletResponse response) {
        if (authentication != null) {
            revokeDomainSession(authentication);
            authorizedClientService.removeAuthorizedClient(authentication.getAuthorizedClientRegistrationId(), authentication.getName());
        }

        // Invalidates the HttpSession + clears SecurityContextHolder — this is what
        // actually ends the session every `.authenticated()` request on this chain
        // relies on; OPSMIND_SESSION is expired below purely so the browser's own
        // cookie jar matches reality, not because anything reads it back.
        new SecurityContextLogoutHandler().logout(request, response, authentication);

        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie(properties.sessionCookieName()).toString());
        return ResponseEntity.noContent().build();
    }

    private void revokeDomainSession(OAuth2AuthenticationToken authentication) {
        if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return;
        }
        String sid = oidcUser.getClaimAsString("sid");
        if (sid == null || sid.isBlank()) {
            return;
        }
        try {
            manageSessionUseCase.logout(new LogoutCommand(
                properties.defaultTenantId(), oidcUser.getIssuer().toString(), oidcUser.getSubject(),
                hashingPort.hash(sid), UUID.randomUUID().toString()
            ));
        } catch (RuntimeException e) {
            log.warn("best-effort domain session revoke failed on browser logout for subject {}: {}", oidcUser.getSubject(), e.getMessage());
        }
    }

    private static ResponseCookie expiredCookie(String name) {
        return ResponseCookie.from(name, "").httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(0).build();
    }
}
