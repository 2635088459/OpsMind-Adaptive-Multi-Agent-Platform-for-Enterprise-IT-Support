package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.internalapi.AutoCloseTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.internalapi.AutoCloseTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AutoCloseTicketUseCase;
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

/** SPEC-TW-027 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code ResolveTicketWithVerificationValidationTest}. */
@WebMvcTest(AutoCloseTicketController.class)
@Import({SecurityConfiguration.class, AutoCloseTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class AutoCloseTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"reason":"Auto-close policy window elapsed without further activity."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AutoCloseTicketUseCase autoCloseTicketUseCase;

    private String route() {
        return "/internal/v1/tickets/" + TICKET_ID + "/auto-close";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "auto-close-scheduler").claim("actor_type", "SERVICE").claim("scope", "ticket:auto-close")))
            .header("If-Match", "\"18\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "auto-close-scheduler").claim("actor_type", "SERVICE").claim("scope", "ticket:auto-close")))
                .header("If-Match", "\"18\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "auto-close-scheduler").claim("actor_type", "SERVICE").claim("scope", "ticket:auto-close")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }

    @Test
    void shouldRejectABlankReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reason":"   "}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }

    @Test
    void shouldRejectATooShortReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reason":"ab"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }

    @Test
    void shouldRejectATooLongReason() throws Exception {
        mockMvc.perform(validRequest().content("{\"reason\":\"" + "a".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }

    @Test
    void shouldRejectAMissingReason() throws Exception {
        mockMvc.perform(validRequest().content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"reason":"Auto-close policy window elapsed without further activity.","reasonCode":"AUTO_CLOSE_TIMEOUT"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(autoCloseTicketUseCase, never()).autoClose(any());
    }
}
