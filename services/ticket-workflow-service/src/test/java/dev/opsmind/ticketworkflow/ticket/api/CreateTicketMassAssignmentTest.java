package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CreateTicketUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the request schema's {@code additionalProperties = false}
 * behavior (SPEC-TW-001 §7): any client-supplied identity, state, or
 * assignment field is rejected before the request ever reaches the
 * application layer.
 */
@WebMvcTest(PublicTicketController.class)
@Import({SecurityConfiguration.class, PublicTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class CreateTicketMassAssignmentTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTicketUseCase createTicketUseCase;

    @ParameterizedTest
    @ValueSource(strings = {
        "\"ticketId\":\"018f0f1e-7b31-7a00-8f42-31f9b25b1a91\"",
        "\"displayId\":\"INC-9999\"",
        "\"requesterId\":\"attacker-controlled-id\"",
        "\"status\":\"RESOLVED\"",
        "\"priority\":\"CRITICAL\"",
        "\"category\":\"HARDWARE\"",
        "\"subcategory\":\"LAPTOP\"",
        "\"assignedTeam\":\"team-1\"",
        "\"assignedAgent\":\"agent-1\"",
        "\"workflowId\":\"wf-1\"",
        "\"approvalId\":\"appr-1\"",
        "\"resolutionCycleId\":\"018f0f1e-0000-7a00-8f42-31f9b25b1a91\"",
        "\"slaCycleId\":\"018f0f1e-0000-7a00-8f42-31f9b25b1a92\"",
        "\"createdAt\":\"2020-01-01T00:00:00Z\"",
        "\"updatedAt\":\"2020-01-01T00:00:00Z\"",
        "\"version\":99",
        "\"attachmentIds\":[\"file-1\"]"
    })
    void shouldRejectForbiddenField(String forbiddenField) throws Exception {
        String body = """
            {"title":"A valid title","description":"A valid description.","applicationCode":"HOUSING_PORTAL","source":"PORTAL",%s}
            """.formatted(forbiddenField);

        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .header("Idempotency-Key", "mass-assignment-test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
