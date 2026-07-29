package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.TicketTimelineController;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTimelineUseCase;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-TW-006 §22: a migration/repair Ticket with no Timeline source still
 * returns {@code 200} with an empty item array, never {@code 404} — the
 * Ticket itself exists and is authorized, only its source rows are absent.
 */
@WebMvcTest(TicketTimelineController.class)
@Import({SecurityConfiguration.class, EmployeeTimelineApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketTimelineEmptyResponseTest {

    private static final UUID TICKET_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketTimelineUseCase getTicketTimelineUseCase;

    @MockitoBean
    private SupportTimelineApiMapper supportTimelineApiMapper;

    @Test
    void shouldReturn200WithEmptyItemsWhenNoTimelineSourceExists() throws Exception {
        TicketTimelineResult result = new TicketTimelineResult(
            TICKET_ID, "INC-2048", TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW,
            List.of(), 50, false, null, Instant.parse("2026-07-25T19:00:00Z")
        );
        when(getTicketTimelineUseCase.getTimeline(any())).thenReturn(result);

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline")
                .with(jwt().jwt(jwt -> jwt.claim("sub", TicketTimelineFixtures.DEFAULT_REQUESTER).claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_" + TicketTimelineFixtures.EMPLOYEE_SCOPE))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.page.hasMore").value(false))
            .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
            .andExpect(jsonPath("$.page.consistency").value("SNAPSHOT"));
    }
}
