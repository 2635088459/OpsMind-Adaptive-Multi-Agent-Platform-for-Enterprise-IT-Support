package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-002 §18: every error follows the shared envelope and never leaks internals. */
@WebMvcTest(PublicTicketQueryController.class)
@Import({SecurityConfiguration.class, PublicTicketQueryApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class GetTicketErrorContractTest {

    private static final String TICKET_ID = GetTicketFixtures.DEFAULT_TICKET_ID.toString();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketUseCase getTicketUseCase;

    @MockitoBean
    private SupportTicketQueryApiMapper supportTicketQueryApiMapper;

    @Test
    void shouldReturnSafeEnvelopeWhenTicketNotFound() throws Exception {
        when(getTicketUseCase.get(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("The Ticket was not found."))
            .andExpect(jsonPath("$.error.details").exists())
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TICKET_ID))));
    }

    @Test
    void shouldReturn500WithoutBodyLeakageWhenSensitiveReadAuditFails() throws Exception {
        when(getTicketUseCase.get(any())).thenThrow(new SensitiveReadAuditFailureException(new RuntimeException("db down")));

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:queue"))))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db down"))));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
