package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterTicketListApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ListRequesterTicketsUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-003 §15: every error follows the shared envelope; cursor errors never leak internals. */
@WebMvcTest(PublicTicketQueryController.class)
@Import({SecurityConfiguration.class, PublicTicketQueryApiMapper.class, RequesterTicketListApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ListRequesterTicketsErrorContractTest {

    private static final String CURSOR = "eyJzZWNyZXQiOiJkbyBub3QgbGVhayJ9.tampered";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketUseCase getTicketUseCase;

    @MockitoBean
    private SupportTicketQueryApiMapper supportTicketQueryApiMapper;

    @MockitoBean
    private ListRequesterTicketsUseCase listRequesterTicketsUseCase;

    @Test
    void shouldReturn400InvalidCursorWithoutLeakingCursorContents() throws Exception {
        when(listRequesterTicketsUseCase.list(any())).thenThrow(new InvalidCursorException());

        mockMvc.perform(get("/api/v1/tickets?cursor=" + CURSOR)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
            .andExpect(jsonPath("$.error.message").value(not(containsString("secret"))))
            .andExpect(jsonPath("$.error.message").value(not(containsString(CURSOR))));
    }

    @Test
    void shouldReturn400ValidationErrorForLimitAboveMaximum() throws Exception {
        mockMvc.perform(get("/api/v1/tickets?limit=100")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn403WhenScopeIsMissing() throws Exception {
        when(listRequesterTicketsUseCase.list(any())).thenThrow(new TicketAuthorizationException("tickets:read:self"));

        mockMvc.perform(get("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
