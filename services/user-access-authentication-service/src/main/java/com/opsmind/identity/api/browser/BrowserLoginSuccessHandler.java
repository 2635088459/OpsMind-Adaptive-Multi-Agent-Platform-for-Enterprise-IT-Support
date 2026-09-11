package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.config.BrowserLoginProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * SPEC-UA-005 (04-use-cases §OIDC login: "... validate callback → establish
 * principal/session metadata"). Runs once Spring Security's own {@code
 * oauth2Login} has already validated state, PKCE, nonce, and exchanged the
 * authorization code for tokens — the actual "link identity / start session /
 * set cookie" work is {@link BrowserSessionEstablisher}'s own (shared with
 * {@link PasswordLoginController}'s direct-grant login); this class's own job
 * is picking where to send the browser next.
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

    private final BrowserSessionEstablisher establisher;
    private final BrowserLoginProperties properties;

    BrowserLoginSuccessHandler(BrowserSessionEstablisher establisher, BrowserLoginProperties properties) {
        this.establisher = establisher;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws java.io.IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String clientId = authentication instanceof OAuth2AuthenticationToken oauth2Token ? oauth2Token.getAuthorizedClientRegistrationId() : null;

        try {
            establisher.establish(oidcUser, clientId, response);
            // SPEC-SC-001: "support-console" is domain 10's own distinct registration
            // (BrowserLoginProperties#supportConsoleSuccessRedirectUri's own javadoc) —
            // every other registration (including SPEC-UA-018's own "opsmind-stepup",
            // which never reaches this success handler at all — see
            // StepUpVerificationSuccessHandler) lands on the original employee-portal target.
            String destination = "support-console".equals(clientId)
                ? properties.supportConsoleSuccessRedirectUri()
                : properties.successRedirectUri();
            response.sendRedirect(destination);
        } catch (UserIdentityNotEligibleException e) {
            // Already denied and audited by ManageSessionUseCase#start itself (INV-UA-002).
            response.sendRedirect(properties.failureRedirectUri());
        }
    }
}
