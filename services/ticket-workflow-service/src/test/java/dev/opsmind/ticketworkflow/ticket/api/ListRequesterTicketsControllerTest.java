package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.ListRequesterTicketsFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterTicketListApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ListRequesterTicketsUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.ListRequesterTicketsQuery;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicTicketQueryController.class)
@Import({SecurityConfiguration.class, PublicTicketQueryApiMapper.class, RequesterTicketListApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ListRequesterTicketsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketUseCase getTicketUseCase;

    @MockitoBean
    private SupportTicketQueryApiMapper supportTicketQueryApiMapper;

    @MockitoBean
    private ListRequesterTicketsUseCase listRequesterTicketsUseCase;

    @Test
    void shouldReturn200WithSummariesAndSecurityHeaders() throws Exception {
        UUID ticketId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        RequesterTicketListResult result = new RequesterTicketListResult(
            List.of(ListRequesterTicketsFixtures.summary(ticketId, now)),
            20, false, null, TicketListFilters.none()
        );
        when(listRequesterTicketsUseCase.list(any())).thenReturn(result);

        mockMvc.perform(get("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Vary", "Authorization"))
            .andExpect(jsonPath("$.items[0].ticketId").value(ticketId.toString()))
            .andExpect(jsonPath("$.items[0].title").value("Cannot sign in to Housing Portal"))
            .andExpect(jsonPath("$.page.limit").value(20))
            .andExpect(jsonPath("$.page.hasMore").value(false))
            .andExpect(jsonPath("$.page.nextCursor").doesNotExist());
    }

    @Test
    void shouldPassLimitAndCursorQueryParametersToTheUseCase() throws Exception {
        when(listRequesterTicketsUseCase.list(any())).thenReturn(
            new RequesterTicketListResult(List.of(), 5, false, null, TicketListFilters.none())
        );

        mockMvc.perform(get("/api/v1/tickets?limit=5&cursor=some-cursor-token")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ListRequesterTicketsQuery> captor = org.mockito.ArgumentCaptor.forClass(ListRequesterTicketsQuery.class);
        org.mockito.Mockito.verify(listRequesterTicketsUseCase).list(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().limit()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().cursor()).isEqualTo("some-cursor-token");
    }

    @Test
    void shouldPassStatusAndApplicationCodeFiltersToTheUseCase() throws Exception {
        when(listRequesterTicketsUseCase.list(any())).thenReturn(
            new RequesterTicketListResult(List.of(), 20, false, null, TicketListFilters.none())
        );

        mockMvc.perform(get("/api/v1/tickets?status=NEW&applicationCode=VPN")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read:self"))))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ListRequesterTicketsQuery> captor = org.mockito.ArgumentCaptor.forClass(ListRequesterTicketsQuery.class);
        org.mockito.Mockito.verify(listRequesterTicketsUseCase).list(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().filters().statuses())
            .containsExactly(dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus.NEW);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().filters().applicationCodes())
            .containsExactly(dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode.VPN);
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/tickets"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
