package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterTicketListApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ListRequesterTicketsUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketListResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-003 §11: an empty list is 200 with items=[], hasMore=false, nextCursor=null — never 404. */
@WebMvcTest(PublicTicketQueryController.class)
@Import({SecurityConfiguration.class, PublicTicketQueryApiMapper.class, RequesterTicketListApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ListRequesterTicketsEmptyResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketUseCase getTicketUseCase;

    @MockitoBean
    private SupportTicketQueryApiMapper supportTicketQueryApiMapper;

    @MockitoBean
    private ListRequesterTicketsUseCase listRequesterTicketsUseCase;

    @Test
    void shouldReturn200WithEmptyItemsWhenEmployeeOwnsNoTickets() throws Exception {
        when(listRequesterTicketsUseCase.list(any())).thenReturn(
            new RequesterTicketListResult(List.of(), 20, false, null, TicketListFilters.none())
        );

        mockMvc.perform(get("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.page.hasMore").value(false))
            .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
            .andExpect(jsonPath("$.appliedFilters.createdFrom").doesNotExist());
    }
}
