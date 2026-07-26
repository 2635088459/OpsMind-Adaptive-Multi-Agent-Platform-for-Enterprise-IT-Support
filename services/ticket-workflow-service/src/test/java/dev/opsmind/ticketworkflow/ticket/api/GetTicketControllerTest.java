package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicTicketQueryController.class)
@Import({SecurityConfiguration.class, PublicTicketQueryApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class GetTicketControllerTest {

    private static final UUID TICKET_ID = GetTicketFixtures.DEFAULT_TICKET_ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketUseCase getTicketUseCase;

    @MockitoBean
    private SupportTicketQueryApiMapper supportTicketQueryApiMapper;

    @Test
    void shouldReturn200WithEmployeeViewAndSecurityHeaders() throws Exception {
        GetTicketResult.Employee result = new GetTicketResult.Employee(GetTicketFixtures.employeeProjection(TICKET_ID));
        when(getTicketUseCase.get(any())).thenReturn(new ConditionalGetResult.Found(result));

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Vary", "Authorization"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(jsonPath("$.ticketId").value(TICKET_ID.toString()))
            .andExpect(jsonPath("$.displayId").value("INC-2048"))
            .andExpect(jsonPath("$.title").value("Cannot sign in to Housing Portal"))
            .andExpect(jsonPath("$.description").value("Duo keeps asking me to enroll again."))
            .andExpect(jsonPath("$.requesterRef").doesNotExist());
    }
}
