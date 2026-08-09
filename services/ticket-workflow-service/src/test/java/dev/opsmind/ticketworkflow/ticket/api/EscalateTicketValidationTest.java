package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.EscalateTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.EscalateTicketController;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.EscalateTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-031 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code UpdateTicketAssignmentValidationTest}. */
@WebMvcTest(EscalateTicketController.class)
@Import({SecurityConfiguration.class, EscalateTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class EscalateTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"escalationReasonCode":"USER_IMPACT","escalationReason":"Customer-facing outage with broad user impact."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EscalateTicketUseCase escalateTicketUseCase;

    private String route() {
        return "/api/v1/tickets/" + TICKET_ID + "/escalation";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:escalate")))
            .header("If-Match", "\"7\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldEscalateSuccessfullyWithAValidRequest() throws Exception {
        when(escalateTicketUseCase.escalate(any())).thenReturn(new EscalateTicketResult(
            TicketId.of(TICKET_ID), TicketStatus.IN_PROGRESS, TicketStatus.ESCALATED, EscalationReasonCode.USER_IMPACT,
            "lead.sam", Instant.parse("2026-08-07T23:00:00Z"), UUID.randomUUID(), 8L, false
        ));

        mockMvc.perform(validRequest().content(VALID_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ESCALATED"));
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:escalate")))
                .header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:escalate")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldRejectAMissingEscalationReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"escalationReason":"Customer-facing outage with broad user impact."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldRejectAnInvalidEscalationReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"escalationReasonCode":"NOT_A_REAL_CODE","escalationReason":"Customer-facing outage with broad user impact."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldRejectABlankReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"escalationReasonCode":"USER_IMPACT","escalationReason":"   "}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldRejectATooShortReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"escalationReasonCode":"USER_IMPACT","escalationReason":"ab"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldRejectATooLongReason() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"escalationReasonCode\":\"USER_IMPACT\",\"escalationReason\":\"" + "a".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"escalationReasonCode":"USER_IMPACT","escalationReason":"Customer-facing outage with broad user impact.","actorId":"someone-else"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(escalateTicketUseCase, never()).escalate(any());
    }
}
