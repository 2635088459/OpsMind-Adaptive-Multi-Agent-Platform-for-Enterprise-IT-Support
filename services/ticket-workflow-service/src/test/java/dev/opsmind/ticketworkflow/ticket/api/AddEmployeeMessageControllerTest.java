package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageController;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AddTicketMessageUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
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

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicTicketMessageController.class)
@Import({SecurityConfiguration.class, PublicTicketMessageApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class AddEmployeeMessageControllerTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;
    private static final String VALID_BODY = """
        {"content":"I restarted the VPN client, but the error still appears."}
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
    void shouldReturn201WithLocationETagAndBodyOnSuccess() throws Exception {
        UUID messageId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-25T18:30:00Z");
        when(addTicketMessageUseCase.addMessage(any())).thenReturn(new AddTicketMessageResult(
            TicketMessageId.of(messageId), TicketId.of(TICKET_ID), TicketMessageType.PUBLIC_REQUESTER_MESSAGE,
            MessageVisibility.PUBLIC, "EMPLOYEE", "I restarted the VPN client, but the error still appears.",
            createdAt, 0L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/tickets/" + TICKET_ID + "/messages/" + messageId))
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.messageId").value(messageId.toString()))
            .andExpect(jsonPath("$.messageType").value("PUBLIC_REQUESTER_MESSAGE"))
            .andExpect(jsonPath("$.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.authorType").value("EMPLOYEE"))
            .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void shouldAddIdempotencyReplayedHeaderOnlyWhenReplayed() throws Exception {
        when(addTicketMessageUseCase.addMessage(any())).thenReturn(new AddTicketMessageResult(
            TicketMessageId.of(UUID.randomUUID()), TicketId.of(TICKET_ID), TicketMessageType.PUBLIC_REQUESTER_MESSAGE,
            MessageVisibility.PUBLIC, "EMPLOYEE", "content", Instant.now(), 0L, true
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
