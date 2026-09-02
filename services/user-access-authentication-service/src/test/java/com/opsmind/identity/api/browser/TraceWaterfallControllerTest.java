package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.dto.TraceWaterfallView;
import com.opsmind.identity.application.exception.TraceAccessDeniedException;
import com.opsmind.identity.application.observability.TraceWaterfallService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SPEC-SC-014: a support-console-only proxy — same registration-gating discipline as {@link com.opsmind.identity.config.SecurityConfig}'s own reasoning. */
@Tag("unit")
class TraceWaterfallControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final TraceWaterfallService service = mock(TraceWaterfallService.class);
    private final TraceWaterfallController controller = new TraceWaterfallController(service);

    private OAuth2AuthenticationToken principal(String registrationId) {
        DefaultOidcUser oidcUser = new DefaultOidcUser(
            List.of(), new OidcIdToken("id-token-value", NOW, NOW.plusSeconds(3600), Map.of("sub", "agent-42", "iss", "https://issuer.example"))
        );
        return new OAuth2AuthenticationToken(oidcUser, List.of(), registrationId);
    }

    @Test
    void aSupportConsoleSessionCanFetchATrace() {
        TraceWaterfallView view = new TraceWaterfallView("trace-1", List.of(), List.of("ticket-workflow"), List.of());
        when(service.fetch("trace-1")).thenReturn(view);

        ResponseEntity<TraceWaterfallView> response = controller.trace("trace-1", principal("support-console"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void anEmployeePortalSessionIsDeniedEvenThoughItIsAValidAuthenticatedSession() {
        assertThatThrownBy(() -> controller.trace("trace-1", principal("opsmind")))
            .isInstanceOf(TraceAccessDeniedException.class);
    }
}
