package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.internalapi.StartVerificationApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.internalapi.StartVerificationController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.StartVerificationUseCase;
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

/** SPEC-TW-022 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code RequestApprovalValidationTest}. */
@WebMvcTest(StartVerificationController.class)
@Import({SecurityConfiguration.class, StartVerificationApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class StartVerificationValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"toolResultId":"tool-result-900","verificationType":"IDENTITY_LOGIN_CHECK",\
        "reason":"Confirm the requester can sign in after MFA reset."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StartVerificationUseCase startVerificationUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/internal/v1/tickets/" + TICKET_ID + "/verification/start")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "verification-orchestrator").claim("actor_type", "SERVICE").claim("scope", "ticket:verification-start")))
            .header("If-Match", "\"60\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/internal/v1/tickets/" + TICKET_ID + "/verification/start")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "verification-orchestrator").claim("actor_type", "SERVICE").claim("scope", "ticket:verification-start")))
                .header("If-Match", "\"60\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(startVerificationUseCase, never()).startVerification(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/internal/v1/tickets/" + TICKET_ID + "/verification/start")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "verification-orchestrator").claim("actor_type", "SERVICE").claim("scope", "ticket:verification-start")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(startVerificationUseCase, never()).startVerification(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(startVerificationUseCase, never()).startVerification(any());
    }

    @Test
    void shouldRejectABlankToolResultId() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"toolResultId":"","verificationType":"IDENTITY_LOGIN_CHECK"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(startVerificationUseCase, never()).startVerification(any());
    }

    @Test
    void shouldRejectAMissingVerificationType() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"toolResultId":"tool-result-900"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(startVerificationUseCase, never()).startVerification(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"toolResultId":"tool-result-900","verificationType":"IDENTITY_LOGIN_CHECK","unexpected":"value"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(startVerificationUseCase, never()).startVerification(any());
    }
}
