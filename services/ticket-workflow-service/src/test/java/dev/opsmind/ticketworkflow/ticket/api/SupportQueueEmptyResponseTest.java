package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportQueueApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryController;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.port.in.QuerySupportQueueUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueScopePort;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-005 §18: an empty Queue is 200 with items=[], hasMore=false, nextCursor=null — never 404. */
@WebMvcTest(SupportTicketQueryController.class)
@Import({SecurityConfiguration.class, SupportQueueApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class SupportQueueEmptyResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuerySupportQueueUseCase querySupportQueueUseCase;

    @MockitoBean
    private SupportQueueScopePort scopePort;

    @MockitoBean
    private RequesterPseudonymizer requesterPseudonymizer;

    @Test
    void shouldReturn200WithEmptyItemsWhenQueueHasNoMatches() throws Exception {
        when(scopePort.resolve(any(), any())).thenReturn(new SupportQueueScope(Set.of(ApplicationCode.HOUSING_PORTAL), Set.of()));
        when(querySupportQueueUseCase.query(any())).thenReturn(
            new SupportQueueResult(List.of(), 25, false, null, Instant.parse("2026-07-25T19:00:00Z"), SupportQueueFixtures.noFilters())
        );

        mockMvc.perform(get("/api/v1/support/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_" + SupportQueueFixtures.QUEUE_READ_SCOPE))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.page.hasMore").value(false))
            .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
            .andExpect(jsonPath("$.appliedFilters.assignedAgent").doesNotExist());
    }
}
