package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportQueueApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.port.in.QuerySupportQueueUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueScopePort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupportTicketQueryController.class)
@Import({SecurityConfiguration.class, SupportQueueApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class SupportQueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuerySupportQueueUseCase querySupportQueueUseCase;

    @MockitoBean
    private SupportQueueScopePort scopePort;

    @MockitoBean
    private RequesterPseudonymizer requesterPseudonymizer;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor supportJwt() {
        return jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                .claim("support_queues", List.of(SupportQueueFixtures.DEFAULT_APPLICATION_CODE))
                .claim("support_teams", List.of(SupportQueueFixtures.DEFAULT_TEAM)))
            .authorities(new SimpleGrantedAuthority("SCOPE_" + SupportQueueFixtures.QUEUE_READ_SCOPE));
    }

    @Test
    void shouldReturn200WithSummariesAndSecurityHeaders() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of(SupportQueueFixtures.DEFAULT_TEAM)));
        UUID ticketId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-25T19:00:00Z");
        SupportQueueResult result = new SupportQueueResult(
            List.of(SupportQueueFixtures.summary(ticketId, now)), 25, false, null, now, SupportQueueFixtures.noFilters()
        );
        when(querySupportQueueUseCase.query(any())).thenReturn(result);

        mockMvc.perform(get("/api/v1/support/tickets").with(supportJwt()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            // SPEC-EP-013/016/017's own real CORS support adds 3 Vary values of its own
            // ahead of the controller's -- this asserts the complete real header, not
            // just the first token (a plain header().string(...) only ever sees that one).
            .andExpect(header().stringValues("Vary", "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers", "Authorization"))
            .andExpect(jsonPath("$.items[0].ticketId").value(ticketId.toString()))
            .andExpect(jsonPath("$.items[0].priority").value("P2"))
            .andExpect(jsonPath("$.page.limit").value(25))
            .andExpect(jsonPath("$.page.hasMore").value(false))
            .andExpect(jsonPath("$.page.consistency").value("LIVE"))
            .andExpect(jsonPath("$.sort.fields[0]").value("slaRank:asc"));
    }

    @Test
    void shouldPassLimitAndCursorQueryParametersToTheUseCase() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of()));
        when(querySupportQueueUseCase.query(any())).thenReturn(
            new SupportQueueResult(List.of(), 5, false, null, Instant.parse("2026-07-25T19:00:00Z"), SupportQueueFixtures.noFilters())
        );

        mockMvc.perform(get("/api/v1/support/tickets?limit=5&cursor=some-cursor-token").with(supportJwt()))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SupportQueueQuery> captor = org.mockito.ArgumentCaptor.forClass(SupportQueueQuery.class);
        org.mockito.Mockito.verify(querySupportQueueUseCase).query(captor.capture());
        assertThat(captor.getValue().limit()).isEqualTo(5);
        assertThat(captor.getValue().cursor()).isEqualTo("some-cursor-token");
    }

    @Test
    void shouldPassFiltersToTheUseCase() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of(SupportQueueFixtures.DEFAULT_TEAM)));
        when(querySupportQueueUseCase.query(any())).thenReturn(
            new SupportQueueResult(List.of(), 25, false, null, Instant.parse("2026-07-25T19:00:00Z"), SupportQueueFixtures.noFilters())
        );

        mockMvc.perform(get("/api/v1/support/tickets?priority=P1&slaState=AT_RISK&unassignedOnly=true").with(supportJwt()))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SupportQueueQuery> captor = org.mockito.ArgumentCaptor.forClass(SupportQueueQuery.class);
        org.mockito.Mockito.verify(querySupportQueueUseCase).query(captor.capture());
        assertThat(captor.getValue().filters().priorities()).contains(dev.opsmind.ticketworkflow.ticket.application.query.SupportQueuePriority.P1);
        assertThat(captor.getValue().filters().slaStates()).contains(dev.opsmind.ticketworkflow.ticket.application.query.SlaQueueState.AT_RISK);
        assertThat(captor.getValue().filters().unassignedOnly()).isTrue();
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/support/tickets"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
