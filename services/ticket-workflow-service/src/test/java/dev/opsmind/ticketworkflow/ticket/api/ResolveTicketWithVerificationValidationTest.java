package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.internalapi.ResolveTicketWithVerificationApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.internalapi.ResolveTicketWithVerificationController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResolveTicketWithVerificationUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-025 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code StartVerificationValidationTest}. */
@WebMvcTest(ResolveTicketWithVerificationController.class)
@Import({SecurityConfiguration.class, ResolveTicketWithVerificationApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ResolveTicketWithVerificationValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"verificationEvidenceId":"ve-300","resolutionCode":"FIXED",\
        "resolutionSummary":"Verification confirmed the requester can sign in after MFA reset."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolveTicketWithVerificationUseCase resolveTicketWithVerificationUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/internal/v1/tickets/" + TICKET_ID + "/verified-resolution")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "verification-orchestrator").claim("actor_type", "SERVICE").claim("scope", "ticket:verified-resolution")))
            .header("If-Match", "\"17\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/internal/v1/tickets/" + TICKET_ID + "/verified-resolution")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "verification-orchestrator").claim("actor_type", "SERVICE").claim("scope", "ticket:verified-resolution")))
                .header("If-Match", "\"17\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/internal/v1/tickets/" + TICKET_ID + "/verified-resolution")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "verification-orchestrator").claim("actor_type", "SERVICE").claim("scope", "ticket:verified-resolution")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }

    @Test
    void shouldRejectABlankVerificationEvidenceId() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"verificationEvidenceId":"","resolutionCode":"FIXED",\
                "resolutionSummary":"Verification confirmed the requester can sign in after MFA reset."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }

    @Test
    void shouldRejectAMissingResolutionCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"verificationEvidenceId":"ve-300",\
                "resolutionSummary":"Verification confirmed the requester can sign in after MFA reset."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }

    @Test
    void shouldRejectATooShortResolutionSummary() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"verificationEvidenceId":"ve-300","resolutionCode":"FIXED","resolutionSummary":"too short"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }

    @Test
    void shouldRejectAnUnrecognizedResolutionCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"verificationEvidenceId":"ve-300","resolutionCode":"NOT_A_REAL_CODE",\
                "resolutionSummary":"Verification confirmed the requester can sign in after MFA reset."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"verificationEvidenceId":"ve-300","resolutionCode":"FIXED",\
                "resolutionSummary":"Verification confirmed the requester can sign in after MFA reset.","unexpected":"value"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketWithVerificationUseCase, never()).resolveWithVerification(any());
    }
}
