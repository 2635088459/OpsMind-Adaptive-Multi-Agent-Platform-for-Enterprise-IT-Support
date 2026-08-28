package com.opsmind.identity.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * SPEC-UA-005's own PKCE-forcing resolver, extended by SPEC-UA-018 to also
 * drive real step-up re-authentication for the {@link
 * StepUpVerificationProperties#REGISTRATION_ID} registration — every other
 * registration (the normal {@code opsmind} login) is untouched, exactly the
 * same PKCE-only behavior SPEC-UA-005 already established.
 *
 * <p>For a step-up initiation request (recognized by its own {@code
 * /oauth2/authorization/opsmind-stepup} path), the caller-supplied {@code
 * challengeId}/{@code nonce}/{@code acr} query parameters — generated and
 * hashed server-side by {@code StepUpChallengeController} before this
 * request is ever issued, never trusted otherwise — are folded into the
 * outgoing Keycloak authorization request: {@code state} carries {@code
 * challengeId.nonce} (round-tripped verbatim by Spring Security's own
 * session-backed authorization-request repository and validated as an
 * ordinary CSRF state value; {@code StepUpVerificationSuccessHandler}
 * parses it back out at callback time), and {@code prompt=login} (plus
 * {@code acr_values} when the challenge itself names a required assurance
 * level) forces Keycloak to perform a genuinely fresh authentication rather
 * than silently reusing an existing SSO session — a stale/reused
 * authentication would prove nothing about the caller's *current* assurance.
 */
public class StepUpAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String STEP_UP_PATH_SUFFIX = "/" + StepUpVerificationProperties.REGISTRATION_ID;

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public StepUpAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository, String authorizationRequestBaseUri) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, authorizationRequestBaseUri);
        this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return applyStepUpParameters(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return applyStepUpParameters(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest applyStepUpParameters(HttpServletRequest request, OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null || !request.getRequestURI().endsWith(STEP_UP_PATH_SUFFIX)) {
            return authorizationRequest;
        }
        String challengeId = request.getParameter("challengeId");
        String nonce = request.getParameter("nonce");
        if (challengeId == null || challengeId.isBlank() || nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("challengeId and nonce are required to initiate step-up re-authentication");
        }

        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.from(authorizationRequest)
            .state(challengeId + "." + nonce)
            .additionalParameters(params -> params.put("prompt", "login"));
        String acr = request.getParameter("acr");
        if (acr != null && !acr.isBlank()) {
            builder.additionalParameters(params -> params.put("acr_values", acr));
        }
        return builder.build();
    }
}
