package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportQueueApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.exception.FilterOutsideAuthorizedScopeException;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.QuerySupportQueueUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueScopePort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-005 §24: every error follows the shared envelope; cursor and scope errors never leak internals. */
@WebMvcTest(SupportTicketQueryController.class)
@Import({SecurityConfiguration.class, SupportQueueApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class SupportQueueErrorContractTest {

    private static final String CURSOR = "eyJzZWNyZXQiOiJkbyBub3QgbGVhayJ9.tampered";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuerySupportQueueUseCase querySupportQueueUseCase;

    @MockitoBean
    private RequesterPseudonymizer requesterPseudonymizer;

    @MockitoBean
    private SupportQueueScopePort scopePort;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor supportJwt() {
        return jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
            .authorities(new SimpleGrantedAuthority("SCOPE_" + SupportQueueFixtures.QUEUE_READ_SCOPE));
    }

    @Test
    void shouldReturn400InvalidCursorWithoutLeakingCursorContents() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of()));
        when(querySupportQueueUseCase.query(any())).thenThrow(new InvalidCursorException());

        mockMvc.perform(get("/api/v1/support/tickets?cursor=" + CURSOR).with(supportJwt()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
            .andExpect(jsonPath("$.error.message").value(not(containsString("secret"))))
            .andExpect(jsonPath("$.error.message").value(not(containsString(CURSOR))));
    }

    @Test
    void shouldReturn400ValidationErrorForLimitAboveMaximum() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of()));

        mockMvc.perform(get("/api/v1/support/tickets?limit=101").with(supportJwt()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn403ForbiddenWhenScopeIsMissing() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of()));
        when(querySupportQueueUseCase.query(any())).thenThrow(new TicketAuthorizationException("tickets:read:queue"));

        mockMvc.perform(get("/api/v1/support/tickets").with(supportJwt()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn403FilterOutsideAuthorizedScopeWithoutLeakingFullScope() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of()));
        when(querySupportQueueUseCase.query(any())).thenThrow(new FilterOutsideAuthorizedScopeException());

        mockMvc.perform(get("/api/v1/support/tickets?applicationCode=VPN").with(supportJwt()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FILTER_OUTSIDE_AUTHORIZED_SCOPE"))
            .andExpect(jsonPath("$.error.message").value(not(containsString("HOUSING_PORTAL"))));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/support/tickets"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
