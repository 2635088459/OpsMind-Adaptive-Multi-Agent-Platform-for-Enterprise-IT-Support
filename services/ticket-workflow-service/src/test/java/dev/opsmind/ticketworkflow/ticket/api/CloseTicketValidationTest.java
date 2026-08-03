package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.CloseTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.CloseTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CloseTicketUseCase;
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

/** SPEC-TW-011 API contract §1: headers, path shape, and request-body Bean Validation. Mirrors {@code ResolveTicketValidationTest}. */
@WebMvcTest(CloseTicketController.class)
@Import({SecurityConfiguration.class, CloseTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class CloseTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_REASON = "Requester confirmed the issue is resolved and no further action is required.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CloseTicketUseCase closeTicketUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/closure")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:close")))
            .header("If-Match", "\"18\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    private String bodyWithReason(String reason) {
        return "{\"closeReasonCode\":\"REQUESTER_CONFIRMED\",\"closeReason\":\"" + reason + "\"}";
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/closure")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:close")))
                .header("If-Match", "\"18\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithReason(VALID_REASON)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(closeTicketUseCase, never()).close(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/closure")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:close")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithReason(VALID_REASON)))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(closeTicketUseCase, never()).close(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(bodyWithReason(VALID_REASON)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400ForAMalformedTicketIdPathSegment() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/not-a-uuid/closure")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:close")))
                .header("If-Match", "\"18\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithReason(VALID_REASON)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingCloseReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("{\"closeReason\":\"" + VALID_REASON + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAnUnsupportedCloseReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("{\"closeReasonCode\":\"NOT_A_CODE\",\"closeReason\":\"" + VALID_REASON + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(closeTicketUseCase, never()).close(any());
    }

    @Test
    void shouldRejectAMissingCloseReason() throws Exception {
        mockMvc.perform(validRequest().content("{\"closeReasonCode\":\"REQUESTER_CONFIRMED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectACloseReasonUnderThreeCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithReason("ab")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectACloseReasonOverFiveHundredCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithReason("a".repeat(501))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"closeReasonCode\":\"REQUESTER_CONFIRMED\",\"closeReason\":\"" + VALID_REASON + "\",\"closedBy\":\"someone-else\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(closeTicketUseCase, never()).close(any());
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(validRequest().content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
