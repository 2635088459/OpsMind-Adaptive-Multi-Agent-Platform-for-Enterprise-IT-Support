package com.opsmind.identity.api.browser;

import com.opsmind.identity.config.StepUpVerificationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * A single {@code oauth2Login} success handler (Spring Security's own DSL
 * only accepts one) fanning out to the right one of two entirely separate
 * flows sharing the same {@code SecurityConfig#browserLoginFilterChain}:
 * SPEC-UA-005's own primary login ({@link BrowserLoginSuccessHandler}) and
 * SPEC-UA-018's own step-up re-authentication ({@link
 * StepUpVerificationSuccessHandler}) — distinguished by which OAuth2 client
 * registration the callback actually came through, exactly the same signal
 * {@link BrowserLoginSuccessHandler} itself already reads to populate the
 * session's own {@code clientId}.
 */
@Component
public class BrowserAuthenticationSuccessDispatcher implements AuthenticationSuccessHandler {

    private final BrowserLoginSuccessHandler loginSuccessHandler;
    private final StepUpVerificationSuccessHandler stepUpSuccessHandler;

    public BrowserAuthenticationSuccessDispatcher(BrowserLoginSuccessHandler loginSuccessHandler, StepUpVerificationSuccessHandler stepUpSuccessHandler) {
        this.loginSuccessHandler = loginSuccessHandler;
        this.stepUpSuccessHandler = stepUpSuccessHandler;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws java.io.IOException {
        String registrationId = authentication instanceof OAuth2AuthenticationToken oauth2Token ? oauth2Token.getAuthorizedClientRegistrationId() : null;
        if (StepUpVerificationProperties.REGISTRATION_ID.equals(registrationId)) {
            stepUpSuccessHandler.onAuthenticationSuccess(request, response, authentication);
        } else {
            loginSuccessHandler.onAuthenticationSuccess(request, response, authentication);
        }
    }
}
