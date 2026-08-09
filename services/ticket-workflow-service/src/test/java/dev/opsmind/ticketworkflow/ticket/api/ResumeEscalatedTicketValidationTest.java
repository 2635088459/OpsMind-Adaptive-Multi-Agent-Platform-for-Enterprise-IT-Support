package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.ResumeEscalatedTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.ResumeEscalatedTicketController;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResumeEscalatedTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
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

/** SPEC-TW-032 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code EscalateTicketValidationTest}. */
@WebMvcTest(ResumeEscalatedTicketController.class)
@Import({SecurityConfiguration.class, ResumeEscalatedTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ResumeEscalatedTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"resumeReasonCode":"ROOT_CAUSE_RESOLVED","resumeReason":"Root cause identified and mitigated; resuming active work."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResumeEscalatedTicketUseCase resumeEscalatedTicketUseCase;

    private String route() {
        return "/api/v1/tickets/" + TICKET_ID + "/escalation/resume";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:escalation-resume")))
            .header("If-Match", "\"7\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldResumeSuccessfullyWithAValidRequest() throws Exception {
        when(resumeEscalatedTicketUseCase.resume(any())).thenReturn(new ResumeEscalatedTicketResult(
            TicketId.of(TICKET_ID), TicketStatus.ESCALATED, TicketStatus.IN_PROGRESS, EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED,
            "lead.sam", Instant.parse("2026-08-08T23:00:00Z"), UUID.randomUUID(), OwnershipStatus.ACTIVE, 8L, false
        ));

        mockMvc.perform(validRequest().content(VALID_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:escalation-resume")))
                .header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:escalation-resume")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldRejectAMissingResumeReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"resumeReason":"Root cause identified and mitigated; resuming active work."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldRejectAnInvalidResumeReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"resumeReasonCode":"NOT_A_REAL_CODE","resumeReason":"Root cause identified and mitigated; resuming active work."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldRejectABlankReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"resumeReasonCode":"ROOT_CAUSE_RESOLVED","resumeReason":"   "}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldRejectATooShortReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"resumeReasonCode":"ROOT_CAUSE_RESOLVED","resumeReason":"ab"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldRejectATooLongReason() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"resumeReasonCode\":\"ROOT_CAUSE_RESOLVED\",\"resumeReason\":\"" + "a".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"resumeReasonCode":"ROOT_CAUSE_RESOLVED","resumeReason":"Root cause identified and mitigated; resuming active work.","actorId":"someone-else"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resumeEscalatedTicketUseCase, never()).resume(any());
    }
}
