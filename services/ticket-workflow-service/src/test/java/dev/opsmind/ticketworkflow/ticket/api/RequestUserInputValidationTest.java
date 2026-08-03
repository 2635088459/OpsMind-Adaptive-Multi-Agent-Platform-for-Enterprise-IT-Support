package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.RequestUserInputApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.RequestUserInputController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.RequestUserInputUseCase;
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

/** SPEC-TW-012 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code CloseTicketValidationTest}. */
@WebMvcTest(RequestUserInputController.class)
@Import({SecurityConfiguration.class, RequestUserInputApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class RequestUserInputValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_PROMPT = "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RequestUserInputUseCase requestUserInputUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/user-input-requests")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-user-input")))
            .header("If-Match", "\"20\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    private String bodyWithPrompt(String prompt) {
        return "{\"prompt\":\"" + prompt + "\"}";
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/user-input-requests")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-user-input")))
                .header("If-Match", "\"20\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithPrompt(VALID_PROMPT)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requestUserInputUseCase, never()).requestUserInput(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/user-input-requests")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-user-input")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithPrompt(VALID_PROMPT)))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(requestUserInputUseCase, never()).requestUserInput(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(bodyWithPrompt(VALID_PROMPT)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400ForAMalformedTicketIdPathSegment() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/not-a-uuid/user-input-requests")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-user-input")))
                .header("If-Match", "\"20\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithPrompt(VALID_PROMPT)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingPrompt() throws Exception {
        mockMvc.perform(validRequest().content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAPromptUnderTenCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithPrompt("too short")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAPromptOverTwoThousandCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWithPrompt("a".repeat(2001))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMoreThanTwentyRequestedFields() throws Exception {
        StringBuilder fields = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                fields.append(",");
            }
            fields.append("\"field").append(i).append("\"");
        }
        fields.append("]");

        mockMvc.perform(validRequest().content(
                "{\"prompt\":\"" + VALID_PROMPT + "\",\"requestedFields\":" + fields + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"prompt\":\"" + VALID_PROMPT + "\",\"requestedBy\":\"someone-else\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requestUserInputUseCase, never()).requestUserInput(any());
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(validRequest().content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
