package com.opsmind.identity.api.browser;

import com.opsmind.identity.support.IdentityContractTestHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real bug found live (against the real running docker-compose stack, not
 * a mocked test): {@link BrowserSessionTokenControllerTest}'s own unit tests
 * all pass with the controller in isolation, but a real anonymous {@code GET
 * /api/v1/session/browser-token} against the actual filter chain returned a
 * {@code 302} to {@code /login} (Spring's own {@code oauth2Login()} default
 * entry point for this chain), not a {@code 401} — a browser {@code fetch()}
 * silently follows that redirect and hands the caller a 200 HTML page
 * instead. This class is the regression test a controller-level unit test
 * structurally cannot catch, since the behavior lives entirely in {@code
 * SecurityConfig#browserLoginFilterChain}'s own exception-handling wiring,
 * not in {@link BrowserSessionTokenController} itself.
 */
@Tag("integration")
class BrowserSessionTokenAuthenticationContractIT extends IdentityContractTestHarness {

    @Test
    void anAnonymousRequestGetsARealFourZeroOneNotARedirectToLogin() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl("/api/v1/session/browser-token"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHENTICATED");
    }
}
