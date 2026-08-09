package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterReopenTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterReopenTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.RequesterReopenTicketUseCase;
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

/** SPEC-TW-028 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code ReopenTicketValidationTest}/{@code ConfirmResolutionValidationTest}. */
@WebMvcTest(RequesterReopenTicketController.class)
@Import({SecurityConfiguration.class, RequesterReopenTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class RequesterReopenTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_REASON = "The requester reported the same endpoint enrollment failure returned after reboot.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RequesterReopenTicketUseCase requesterReopenTicketUseCase;

    private String route() {
        return "/api/v1/tickets/" + TICKET_ID + "/reopen-request";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:reopen-request")))
            .header("If-Match", "\"19\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    private String bodyWithReason(String reason) {
        return "{\"reopenReasonCode\":\"REQUESTER_REPORTED_NOT_FIXED\",\"reopenReason\":\"" + reason + "\"}";
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:reopen-request")))
                .header("If-Match", "\"19\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithReason(VALID_REASON)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:reopen-request")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithReason(VALID_REASON)))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(bodyWithReason(VALID_REASON)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldRejectAMissingReopenReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("{\"reopenReason\":\"" + VALID_REASON + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldRejectAnUnsupportedReopenReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("{\"reopenReasonCode\":\"NOT_A_CODE\",\"reopenReason\":\"" + VALID_REASON + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldRejectAMissingReopenReason() throws Exception {
        mockMvc.perform(validRequest().content("{\"reopenReasonCode\":\"REQUESTER_REPORTED_NOT_FIXED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldRejectAReopenReasonUnderTenCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithReason("too short")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldRejectAReopenReasonOverOneThousandCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithReason("a".repeat(1001))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"reopenReasonCode\":\"REQUESTER_REPORTED_NOT_FIXED\",\"reopenReason\":\"" + VALID_REASON + "\",\"reopenedBy\":\"someone-else\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requesterReopenTicketUseCase, never()).reopen(any());
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(validRequest().content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
