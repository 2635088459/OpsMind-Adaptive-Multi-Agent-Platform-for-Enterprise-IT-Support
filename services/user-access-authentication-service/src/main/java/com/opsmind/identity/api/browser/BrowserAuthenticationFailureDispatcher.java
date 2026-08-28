package com.opsmind.identity.api.browser;

import com.opsmind.identity.config.StepUpVerificationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * {@link BrowserAuthenticationSuccessDispatcher}'s own failure-side twin.
 * No {@code Authentication} exists yet on a rejected callback, so the
 * registration is read from the callback URL itself ({@code
 * /login/oauth2/code/{registrationId}}) instead.
 */
@Component
public class BrowserAuthenticationFailureDispatcher implements AuthenticationFailureHandler {

    private final BrowserLoginFailureHandler loginFailureHandler;
    private final StepUpVerificationFailureHandler stepUpFailureHandler;

    public BrowserAuthenticationFailureDispatcher(BrowserLoginFailureHandler loginFailureHandler, StepUpVerificationFailureHandler stepUpFailureHandler) {
        this.loginFailureHandler = loginFailureHandler;
        this.stepUpFailureHandler = stepUpFailureHandler;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws java.io.IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.endsWith("/" + StepUpVerificationProperties.REGISTRATION_ID)) {
            stepUpFailureHandler.onAuthenticationFailure(request, response, exception);
        } else {
            loginFailureHandler.onAuthenticationFailure(request, response, exception);
        }
    }
}
