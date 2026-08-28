package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.exception.StepUpChallengeNotFoundException;
import com.opsmind.identity.application.exception.StepUpEvidenceRejectedException;
import com.opsmind.identity.application.port.in.ManageStepUpUseCase;
import com.opsmind.identity.config.StepUpVerificationProperties;
import com.opsmind.identity.domain.stepup.IllegalStepUpTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SPEC-UA-018 (Step Up Proof Verification — 04-use-cases §Step-up: "Create
 * challenge → Keycloak MFA → verify evidence → consume once"). Runs once
 * Spring Security's own {@code oauth2Login} has already validated state,
 * PKCE, and nonce and exchanged the code for a genuinely fresh (forced by
 * {@code StepUpAuthorizationRequestResolver}'s own {@code prompt=login})
 * re-authentication — this handler's own job is turning that verified
 * {@link OidcUser} into real evidence for {@link
 * ManageStepUpUseCase#verify}, never trusting anything the browser itself
 * could have supplied. {@code state} carries {@code challengeId.nonce}
 * exactly as the resolver set it; Spring's own authorization-request
 * repository already guarantees this exact value round-tripped unchanged
 * (ordinary OAuth2 state/CSRF protection) before this handler ever runs.
 */
@Component
public class StepUpVerificationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(StepUpVerificationSuccessHandler.class);

    private final ManageStepUpUseCase manageStepUpUseCase;
    private final StepUpVerificationProperties properties;

    public StepUpVerificationSuccessHandler(ManageStepUpUseCase manageStepUpUseCase, StepUpVerificationProperties properties) {
        this.manageStepUpUseCase = manageStepUpUseCase;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws java.io.IOException {
        String state = request.getParameter("state");
        int separator = state == null ? -1 : state.indexOf('.');
        if (separator <= 0) {
            log.warn("step-up callback arrived with a malformed state value");
            response.sendRedirect(properties.failureRedirectUri());
            return;
        }
        String challengeId = state.substring(0, separator);
        String rawNonce = state.substring(separator + 1);

        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        VerifyStepUpChallengeCommand command = new VerifyStepUpChallengeCommand(
            challengeId, oidcUser.getIssuer().toString(), oidcUser.getSubject(), oidcUser.getClaimAsString("acr"),
            oidcUser.getClaimAsStringList("amr"), rawNonce, UUID.randomUUID().toString()
        );

        try {
            manageStepUpUseCase.verify(command);
            response.sendRedirect(properties.successRedirectUri());
        } catch (StepUpChallengeNotFoundException | StepUpEvidenceRejectedException | IllegalStepUpTransitionException e) {
            // Already audited by ManageStepUpService#verify itself (STEPUP_FAILED) — this handler only redirects.
            log.warn("step-up verification rejected: {}", e.getClass().getSimpleName());
            response.sendRedirect(properties.failureRedirectUri());
        }
    }
}
