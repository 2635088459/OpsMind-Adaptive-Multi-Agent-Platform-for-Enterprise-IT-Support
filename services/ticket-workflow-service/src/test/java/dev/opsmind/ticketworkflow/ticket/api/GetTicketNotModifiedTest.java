package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterTicketListApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ListRequesterTicketsUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-002 §15: a matching If-None-Match yields an empty 304 with the same ETag. */
@WebMvcTest(PublicTicketQueryController.class)
@Import({SecurityConfiguration.class, PublicTicketQueryApiMapper.class, RequesterTicketListApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class GetTicketNotModifiedTest {

    private static final String TICKET_ID = GetTicketFixtures.DEFAULT_TICKET_ID.toString();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketUseCase getTicketUseCase;

    @MockitoBean
    private SupportTicketQueryApiMapper supportTicketQueryApiMapper;

    @MockitoBean
    private ListRequesterTicketsUseCase listRequesterTicketsUseCase;

    @Test
    void shouldReturn304WithEmptyBodyWhenNotModified() throws Exception {
        when(getTicketUseCase.get(any())).thenReturn(new ConditionalGetResult.NotModified(0L));

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID)
                .header("If-None-Match", "\"0\"")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(content().string(""));
    }
}
