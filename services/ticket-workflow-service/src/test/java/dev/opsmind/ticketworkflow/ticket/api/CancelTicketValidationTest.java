package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.CancelTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.CancelTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CancelTicketUseCase;
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

/** SPEC-TW-029 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code ConfirmResolutionValidationTest}. */
@WebMvcTest(CancelTicketController.class)
@Import({SecurityConfiguration.class, CancelTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class CancelTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"cancelReasonCode":"NO_LONGER_NEEDED","cancelReason":"The requester no longer needs this request."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CancelTicketUseCase cancelTicketUseCase;

    private String route() {
        return "/api/v1/tickets/" + TICKET_ID + "/cancel";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:cancel")))
            .header("If-Match", "\"5\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:cancel")))
                .header("If-Match", "\"5\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE").claim("scope", "ticket:cancel")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldRejectAMissingCancelReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"cancelReason":"The requester no longer needs this request."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldRejectAnUnrecognizedCancelReasonCode() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"cancelReasonCode":"NOT_A_REAL_CODE","cancelReason":"The requester no longer needs this request."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldRejectABlankCancelReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"cancelReasonCode":"NO_LONGER_NEEDED","cancelReason":"   "}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldRejectATooShortCancelReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"cancelReasonCode":"NO_LONGER_NEEDED","cancelReason":"ab"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldRejectATooLongCancelReason() throws Exception {
        mockMvc.perform(validRequest().content("{\"cancelReasonCode\":\"NO_LONGER_NEEDED\",\"cancelReason\":\"" + "a".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"cancelReasonCode":"NO_LONGER_NEEDED","cancelReason":"The requester no longer needs this request.",\
                "cancelledBy":"someone-else"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(cancelTicketUseCase, never()).cancel(any());
    }
}
