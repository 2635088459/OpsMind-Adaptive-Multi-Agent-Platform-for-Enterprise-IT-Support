package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** SPEC-UA-005 (04-use-cases §OIDC login: "Reject and audit mismatched state/nonce/code"). */
@Tag("unit")
class BrowserLoginFailureHandlerTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final AuditPort auditPort = mock(AuditPort.class);
    private final ClockPort clock = () -> now;
    private final BrowserLoginProperties properties = new BrowserLoginProperties("tenant-x", Duration.ofHours(2), "MY_COOKIE", "/home", "/login?error", "/support-console-home");
    private final BrowserLoginFailureHandler handler = new BrowserLoginFailureHandler(auditPort, clock, properties);

    @Test
    void auditsTheDenialWithoutLeakingTokenInternalsAndRedirectsToTheFailureUri() throws Exception {
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
            new OAuth2Error("invalid_state_parameter"), "state parameter was eyJhbGciOiJIUzI1NiJ9-shaped, not a plain nonce"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");

        ArgumentCaptor<IdentityAuditRecord> captor = ArgumentCaptor.forClass(IdentityAuditRecord.class);
        verify(auditPort).record(captor.capture());
        IdentityAuditRecord recorded = captor.getValue();
        assertThat(recorded.tenantId().value()).isEqualTo("tenant-x");
        assertThat(recorded.action()).isEqualTo(IdentityAuditAction.USER_SESSION_STARTED);
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(recorded.reasonCode()).isEqualTo("OAuth2AuthenticationException");
        assertThat(recorded.reasonCode()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
    }
}
