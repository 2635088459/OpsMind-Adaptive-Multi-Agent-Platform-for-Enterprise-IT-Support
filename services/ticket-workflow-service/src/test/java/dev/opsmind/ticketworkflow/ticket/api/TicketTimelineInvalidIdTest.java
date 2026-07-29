package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.TicketTimelineController;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTimelineUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-006 §4: {@code ticketId} must be a canonical UUID; a malformed path variable returns 400 VALIDATION_ERROR. */
@WebMvcTest(TicketTimelineController.class)
@Import({SecurityConfiguration.class, EmployeeTimelineApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketTimelineInvalidIdTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketTimelineUseCase getTicketTimelineUseCase;

    @MockitoBean
    private SupportTimelineApiMapper supportTimelineApiMapper;

    @Test
    void shouldReturn400ForMalformedTicketIdPathVariable() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/not-a-uuid/timeline")
                .with(jwt().jwt(jwt -> jwt.claim("sub", TicketTimelineFixtures.DEFAULT_REQUESTER).claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_" + TicketTimelineFixtures.EMPLOYEE_SCOPE))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(getTicketTimelineUseCase);
    }

    @Test
    void shouldReturn400ForOutOfRangeLimit() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/timeline?limit=0")
                .with(jwt().jwt(jwt -> jwt.claim("sub", TicketTimelineFixtures.DEFAULT_REQUESTER).claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_" + TicketTimelineFixtures.EMPLOYEE_SCOPE))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400ForLimitAboveMaximum() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/timeline?limit=101")
                .with(jwt().jwt(jwt -> jwt.claim("sub", TicketTimelineFixtures.DEFAULT_REQUESTER).claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_" + TicketTimelineFixtures.EMPLOYEE_SCOPE))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
