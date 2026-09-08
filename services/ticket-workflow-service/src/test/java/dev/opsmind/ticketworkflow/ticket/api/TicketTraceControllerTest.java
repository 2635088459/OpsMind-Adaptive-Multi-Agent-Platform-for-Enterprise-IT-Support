package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.TicketTraceController;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTraceUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-SC-014: {@code GET /api/v1/tickets/{ticketId}/trace} returns the
 * Ticket's latest trace id (or {@code null}) for a support actor with the
 * internal-timeline scope, and 403s otherwise. It never touches the governed
 * Get Ticket / Timeline contracts.
 */
@WebMvcTest(TicketTraceController.class)
@Import({SecurityConfiguration.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketTraceControllerTest {

    private static final UUID TICKET_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketTraceUseCase getTicketTraceUseCase;

    private static RequestPostProcessor supportInternalJwt() {
        return jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
            .authorities(
                new SimpleGrantedAuthority("SCOPE_tickets:read:queue"),
                new SimpleGrantedAuthority("SCOPE_tickets:timeline:internal")
            );
    }

    @Test
    void returnsTheLatestTraceId() throws Exception {
        when(getTicketTraceUseCase.latestTraceId(eq(TicketId.of(TICKET_ID)), any(ActorContext.class)))
            .thenReturn(Optional.of("04c2885e94ba49bbdd7c187f1ff5a3a8"));

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/trace").with(supportInternalJwt()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(jsonPath("$.traceId").value("04c2885e94ba49bbdd7c187f1ff5a3a8"));
    }

    @Test
    void serializesAnAbsentTraceIdAsNull() throws Exception {
        when(getTicketTraceUseCase.latestTraceId(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/trace").with(supportInternalJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.traceId").value((Object) null));
    }

    @Test
    void forwardsAnAuthorizationFailureAs403() throws Exception {
        when(getTicketTraceUseCase.latestTraceId(any(), any()))
            .thenThrow(new TicketAuthorizationException("tickets:timeline:internal"));

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/trace").with(supportInternalJwt()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
