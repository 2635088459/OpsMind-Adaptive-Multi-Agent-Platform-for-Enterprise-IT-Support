package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.exception.StepUpEvidenceRejectedException;
import com.opsmind.identity.application.port.in.ManageStepUpUseCase;
import com.opsmind.identity.config.StepUpVerificationProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SPEC-UA-018: {@link StepUpVerificationSuccessHandler} runs only after
 * Spring Security's own {@code oauth2Login} has already validated
 * state/PKCE/nonce and exchanged the code for a genuinely fresh
 * re-authentication — these tests construct the resulting {@code OidcUser}
 * directly and assert this class's own glue logic: parsing {@code state}
 * back into {@code challengeId}/{@code nonce}, building real evidence, and
 * redirecting based on the outcome.
 */
@Tag("unit")
class StepUpVerificationSuccessHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ManageStepUpUseCase manageStepUpUseCase = mock(ManageStepUpUseCase.class);
    private final StepUpVerificationProperties properties = new StepUpVerificationProperties("/back-to-work", "/step-up?error");
    private final StepUpVerificationSuccessHandler handler = new StepUpVerificationSuccessHandler(manageStepUpUseCase, properties);

    private OAuth2AuthenticationToken authenticationWithClaims(Map<String, Object> extraClaims) {
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("iss", "https://idp.example/realms/opsmind");
        claims.put("sub", "sub-1");
        claims.putAll(extraClaims);
        OidcIdToken idToken = new OidcIdToken("raw-id-token", NOW, NOW.plusSeconds(300), claims);
        OidcUserInfo userInfo = new OidcUserInfo(Map.of("sub", "sub-1"));
        DefaultOidcUser oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, userInfo, "sub");
        return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), StepUpVerificationProperties.REGISTRATION_ID);
    }

    private MockHttpServletRequest requestWithState(String state) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (state != null) {
            request.setParameter("state", state);
        }
        return request;
    }

    @Test
    void parsesStateAndVerifiesWithRealEvidenceThenRedirectsToSuccess() throws Exception {
        MockHttpServletRequest request = requestWithState("challenge-1.raw-nonce-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationToken authentication = authenticationWithClaims(Map.of("acr", "AAL2", "amr", List.of("pwd", "otp")));

        handler.onAuthenticationSuccess(request, response, authentication);

        var command = captureCommand();
        assertThat(command.stepUpChallengeId()).isEqualTo("challenge-1");
        assertThat(command.issuer()).isEqualTo("https://idp.example/realms/opsmind");
        assertThat(command.subject()).isEqualTo("sub-1");
        assertThat(command.acr()).isEqualTo("AAL2");
        assertThat(command.amr()).containsExactly("pwd", "otp");
        assertThat(command.rawNonce()).isEqualTo("raw-nonce-value");
        assertThat(response.getRedirectedUrl()).isEqualTo("/back-to-work");
    }

    @Test
    void redirectsToFailureWhenTheStateIsMalformed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithState("no-dot-here"), response, authenticationWithClaims(Map.of()));

        assertThat(response.getRedirectedUrl()).isEqualTo("/step-up?error");
        verifyNoInteractions(manageStepUpUseCase);
    }

    @Test
    void redirectsToFailureWhenTheStateIsAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithState(null), response, authenticationWithClaims(Map.of()));

        assertThat(response.getRedirectedUrl()).isEqualTo("/step-up?error");
        verify(manageStepUpUseCase, never()).verify(any());
    }

    @Test
    void redirectsToFailureWhenVerificationRejectsTheEvidence() throws Exception {
        when(manageStepUpUseCase.verify(any())).thenThrow(new StepUpEvidenceRejectedException("challenge-1", "nonce mismatch"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithState("challenge-1.raw-nonce-value"), response, authenticationWithClaims(Map.of()));

        assertThat(response.getRedirectedUrl()).isEqualTo("/step-up?error");
    }

    private VerifyStepUpChallengeCommand captureCommand() {
        org.mockito.ArgumentCaptor<VerifyStepUpChallengeCommand> captor = org.mockito.ArgumentCaptor.forClass(VerifyStepUpChallengeCommand.class);
        verify(manageStepUpUseCase).verify(captor.capture());
        return captor.getValue();
    }
}
