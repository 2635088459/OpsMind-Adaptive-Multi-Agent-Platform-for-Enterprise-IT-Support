package com.opsmind.identity.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC-UA-018: the normal {@code opsmind} registration must stay untouched
 * (SPEC-UA-005's own PKCE-only behavior) while {@code opsmind-stepup} gets
 * the real step-up parameters folded into the outgoing authorization
 * request.
 */
@Tag("unit")
class StepUpAuthorizationRequestResolverTest {

    private final ClientRegistration loginRegistration = registration("opsmind");
    private final ClientRegistration stepUpRegistration = registration("opsmind-stepup");
    private final ClientRegistrationRepository clientRegistrationRepository = new InMemoryClientRegistrationRepository(loginRegistration, stepUpRegistration);
    private final StepUpAuthorizationRequestResolver resolver = new StepUpAuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization");

    private static ClientRegistration registration(String registrationId) {
        return ClientRegistration.withRegistrationId(registrationId)
            .clientId("client-1")
            .clientSecret("secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid")
            .authorizationUri("https://idp.example/realms/opsmind/protocol/openid-connect/auth")
            .tokenUri("https://idp.example/realms/opsmind/protocol/openid-connect/token")
            .build();
    }

    @Test
    void leavesTheNormalLoginRegistrationUntouchedBesidesPkce() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/authorization/opsmind");

        OAuth2AuthorizationRequest resolved = resolver.resolve(request, "opsmind");

        assertThat(resolved.getAdditionalParameters()).containsKey("code_challenge");
        assertThat(resolved.getAdditionalParameters()).doesNotContainKey("prompt");
        assertThat(resolved.getAdditionalParameters()).doesNotContainKey("acr_values");
    }

    @Test
    void foldsChallengeIdAndNonceIntoStateAndForcesPromptLoginForStepUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/authorization/opsmind-stepup");
        request.setParameter("challengeId", "challenge-1");
        request.setParameter("nonce", "raw-nonce-value");

        OAuth2AuthorizationRequest resolved = resolver.resolve(request, "opsmind-stepup");

        assertThat(resolved.getState()).isEqualTo("challenge-1.raw-nonce-value");
        assertThat(resolved.getAdditionalParameters()).containsEntry("prompt", "login");
        assertThat(resolved.getAdditionalParameters()).containsKey("code_challenge");
        assertThat(resolved.getAdditionalParameters()).doesNotContainKey("acr_values");
    }

    @Test
    void includesAcrValuesWhenTheInitiationRequestNamesOne() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/authorization/opsmind-stepup");
        request.setParameter("challengeId", "challenge-1");
        request.setParameter("nonce", "raw-nonce-value");
        request.setParameter("acr", "AAL2");

        OAuth2AuthorizationRequest resolved = resolver.resolve(request, "opsmind-stepup");

        assertThat(resolved.getAdditionalParameters()).containsEntry("acr_values", "AAL2");
    }

    @Test
    void rejectsAStepUpInitiationMissingChallengeIdOrNonce() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/authorization/opsmind-stepup");

        assertThatThrownBy(() -> resolver.resolve(request, "opsmind-stepup"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
