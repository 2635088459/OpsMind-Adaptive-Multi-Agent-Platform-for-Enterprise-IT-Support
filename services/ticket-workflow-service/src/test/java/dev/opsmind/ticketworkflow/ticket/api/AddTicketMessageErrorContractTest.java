package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageController;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketMessageNotAllowedInStateException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AddTicketMessageUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionPolicy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-004 §17: every error follows the shared envelope. */
@WebMvcTest(PublicTicketMessageController.class)
@Import({SecurityConfiguration.class, PublicTicketMessageApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class AddTicketMessageErrorContractTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;
    private static final String VALID_BODY = """
        {"content":"valid content"}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddTicketMessageUseCase addTicketMessageUseCase;

    @MockitoBean
    private SecretDetectionPolicy secretDetectionPolicy;

    @MockitoBean
    private SecretDetectionAuditRecorder secretDetectionAuditRecorder;

    @MockitoBean
    private TicketTelemetry ticketTelemetry;

    @Test
    void shouldReturn404WhenTicketIsMissingOrHidden() throws Exception {
        when(addTicketMessageUseCase.addMessage(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn409WhenTicketIsInATerminalState() throws Exception {
        when(addTicketMessageUseCase.addMessage(any())).thenThrow(new TicketMessageNotAllowedInStateException());

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MESSAGE_NOT_ALLOWED_IN_STATE"));
    }

    @Test
    void shouldReturn409WhenIdempotencyKeyIsReusedWithDifferentPayload() throws Exception {
        when(addTicketMessageUseCase.addMessage(any())).thenThrow(new IdempotencyKeyReusedException("key-3"));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
}
