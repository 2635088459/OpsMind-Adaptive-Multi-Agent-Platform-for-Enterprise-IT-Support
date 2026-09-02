package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.TicketTimelineController;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineItemResponse;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineResponse;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTimelineUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-TW-006 §3, §7: the single physical Timeline route dispatches to the
 * Employee or Support wire shape purely from the resolved view on {@link
 * TicketTimelineResult}, and the client cannot elevate that view through a
 * query parameter or header (§7).
 */
@WebMvcTest(TicketTimelineController.class)
@Import({SecurityConfiguration.class, EmployeeTimelineApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketTimelineControllerTest {

    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-07-25T19:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketTimelineUseCase getTicketTimelineUseCase;

    @MockitoBean
    private SupportTimelineApiMapper supportTimelineApiMapper;

    private static RequestPostProcessor employeeJwt() {
        return jwt().jwt(jwt -> jwt.claim("sub", TicketTimelineFixtures.DEFAULT_REQUESTER).claim("actor_type", "EMPLOYEE"))
            .authorities(new SimpleGrantedAuthority("SCOPE_" + TicketTimelineFixtures.EMPLOYEE_SCOPE));
    }

    private static RequestPostProcessor supportJwt() {
        return jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                .claim("support_queues", List.of(TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)))
            .authorities(new SimpleGrantedAuthority("SCOPE_" + TicketTimelineFixtures.SUPPORT_SCOPE));
    }

    private TicketTimelineResult employeeResult() {
        return new TicketTimelineResult(
            TICKET_ID, "INC-2048", TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW,
            List.of(TicketTimelineFixtures.ticketCreatedItem(TICKET_ID, SNAPSHOT_AT.minusSeconds(100))),
            50, false, null, SNAPSHOT_AT
        );
    }

    @Test
    void shouldReturn200WithEmployeeViewAndSecurityHeaders() throws Exception {
        when(getTicketTimelineUseCase.getTimeline(any())).thenReturn(employeeResult());

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline").with(employeeJwt()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Pragma", "no-cache"))
            // SPEC-EP-013/016/017's own real CORS support adds 3 Vary values of its own
            // ahead of the controller's -- this asserts the complete real header, not
            // just the first token (a plain header().string(...) only ever sees that one).
            .andExpect(header().stringValues("Vary", "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers", "Authorization"))
            .andExpect(jsonPath("$.ticketId").value(TICKET_ID.toString()))
            .andExpect(jsonPath("$.displayId").value("INC-2048"))
            .andExpect(jsonPath("$.viewType").value("EMPLOYEE_PUBLIC_VIEW"))
            .andExpect(jsonPath("$.items[0].itemType").value("TICKET_CREATED"))
            .andExpect(jsonPath("$.page.consistency").value("SNAPSHOT"))
            .andExpect(jsonPath("$.sort.fields[0]").value("occurredAt:asc"));
    }

    @Test
    void shouldReturn200WithSupportViewAndDelegateToSupportMapper() throws Exception {
        TicketTimelineResult result = new TicketTimelineResult(
            TICKET_ID, "INC-2048", TicketTimelineViewType.SUPPORT_PUBLIC_VIEW, List.of(), 50, false, null, SNAPSHOT_AT
        );
        when(getTicketTimelineUseCase.getTimeline(any())).thenReturn(result);

        SupportTimelineResponse.Page page = new SupportTimelineResponse.Page(50, false, null, SNAPSHOT_AT, "SNAPSHOT");
        SupportTimelineResponse.Sort sort = new SupportTimelineResponse.Sort(1, List.of("occurredAt:asc", "itemTypeRank:asc", "itemId:asc"));
        SupportTimelineResponse mapped = new SupportTimelineResponse(TICKET_ID, "INC-2048", "SUPPORT_PUBLIC_VIEW", List.<SupportTimelineItemResponse>of(), page, sort);
        when(supportTimelineApiMapper.toResponse(result)).thenReturn(mapped);

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline").with(supportJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewType").value("SUPPORT_PUBLIC_VIEW"))
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void shouldPassLimitAndCursorQueryParametersToTheUseCase() throws Exception {
        when(getTicketTimelineUseCase.getTimeline(any())).thenReturn(employeeResult());

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline?limit=10&cursor=some-cursor-token").with(employeeJwt()))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<TicketTimelineQuery> captor = org.mockito.ArgumentCaptor.forClass(TicketTimelineQuery.class);
        org.mockito.Mockito.verify(getTicketTimelineUseCase).getTimeline(captor.capture());
        assertThat(captor.getValue().limit()).isEqualTo(10);
        assertThat(captor.getValue().cursor()).isEqualTo("some-cursor-token");
        assertThat(captor.getValue().ticketId().value()).isEqualTo(TICKET_ID);
    }

    @Test
    void shouldRejectClientSuppliedIncludeInternalQueryParameter() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline?includeInternal=true").with(employeeJwt()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        org.mockito.Mockito.verifyNoInteractions(getTicketTimelineUseCase);
    }

    @Test
    void shouldRejectClientSuppliedIncludeInternalHeader() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline")
                .header("X-Include-Internal", "true")
                .with(employeeJwt()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        org.mockito.Mockito.verifyNoInteractions(getTicketTimelineUseCase);
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
