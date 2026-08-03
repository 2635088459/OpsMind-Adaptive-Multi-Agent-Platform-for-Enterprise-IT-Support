package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.ResolveTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.ResolveTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResolveTicketUseCase;
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

/** SPEC-TW-010 API contract §1/§6: headers, path shape, and request-body Bean Validation. Mirrors {@code TicketAssignmentValidationTest}. */
@WebMvcTest(ResolveTicketController.class)
@Import({SecurityConfiguration.class, ResolveTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ResolveTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_SUMMARY = "Reinstalled the endpoint management profile and confirmed check-in.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolveTicketUseCase resolveTicketUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/resolution")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:resolve")))
            .header("If-Match", "\"17\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    private String bodyWithSummary(String summary) {
        return "{\"resolutionCode\":\"FIXED\",\"resolutionSummary\":\"" + summary + "\"}";
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/resolution")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:resolve")))
                .header("If-Match", "\"17\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithSummary(VALID_SUMMARY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketUseCase, never()).resolve(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/resolution")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:resolve")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithSummary(VALID_SUMMARY)))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(resolveTicketUseCase, never()).resolve(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(bodyWithSummary(VALID_SUMMARY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400ForAMalformedTicketIdPathSegment() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/not-a-uuid/resolution")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:resolve")))
                .header("If-Match", "\"17\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithSummary(VALID_SUMMARY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingResolutionCode() throws Exception {
        mockMvc.perform(validRequest().content("{\"resolutionSummary\":\"" + VALID_SUMMARY + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAnUnsupportedResolutionCode() throws Exception {
        mockMvc.perform(validRequest().content("{\"resolutionCode\":\"NOT_A_CODE\",\"resolutionSummary\":\"" + VALID_SUMMARY + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketUseCase, never()).resolve(any());
    }

    @Test
    void shouldRejectAMissingResolutionSummary() throws Exception {
        mockMvc.perform(validRequest().content("{\"resolutionCode\":\"FIXED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAResolutionSummaryUnderTenCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithSummary("too short")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAResolutionSummaryOverFiveThousandCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithSummary("a".repeat(5001))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"resolutionCode\":\"FIXED\",\"resolutionSummary\":\"" + VALID_SUMMARY + "\",\"resolvedBy\":\"someone-else\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(resolveTicketUseCase, never()).resolve(any());
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(validRequest().content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
