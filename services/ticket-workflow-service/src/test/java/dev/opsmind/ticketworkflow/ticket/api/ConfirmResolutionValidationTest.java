package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.ConfirmResolutionApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.ConfirmResolutionController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ConfirmResolutionUseCase;
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

/** SPEC-TW-026 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code CloseTicketValidationTest}/{@code UserReplyValidationTest}. */
@WebMvcTest(ConfirmResolutionController.class)
@Import({SecurityConfiguration.class, ConfirmResolutionApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ConfirmResolutionValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"reasonCode":"REQUESTER_CONFIRMED",\
        "reason":"Requester confirmed the issue is resolved and no further action is required."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConfirmResolutionUseCase confirmResolutionUseCase;

    private String route() {
        return "/api/v1/tickets/" + TICKET_ID + "/resolution-confirmation";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:resolution-confirm")))
            .header("If-Match", "\"18\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:resolution-confirm")))
                .header("If-Match", "\"18\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:resolution-confirm")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldRejectAMissingReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reason":"Requester confirmed the issue is resolved and no further action is required."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldRejectAnAdministrativeCloseReasonCodeThatIsNotAConfirmation() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reasonCode":"AUTO_CLOSE_TIMEOUT",\
                "reason":"Requester confirmed the issue is resolved and no further action is required."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldRejectAnUnrecognizedReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reasonCode":"NOT_A_REAL_CODE",\
                "reason":"Requester confirmed the issue is resolved and no further action is required."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldRejectABlankReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reasonCode":"REQUESTER_CONFIRMED","reason":"   "}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldRejectATooShortReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reasonCode":"REQUESTER_CONFIRMED","reason":"ab"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldRejectATooLongReason() throws Exception {
        mockMvc.perform(validRequest().content("{\"reasonCode\":\"REQUESTER_CONFIRMED\",\"reason\":\"" + "a".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reasonCode":"REQUESTER_CONFIRMED",\
                "reason":"Requester confirmed the issue is resolved and no further action is required.",\
                "confirmedBy":"someone-else"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(confirmResolutionUseCase, never()).confirmResolution(any());
    }
}
