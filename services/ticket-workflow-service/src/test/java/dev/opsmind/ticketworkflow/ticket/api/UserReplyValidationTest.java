package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.UserReplyApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.UserReplyController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UserReplyAndResumeUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionPolicy;
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

/** SPEC-TW-013 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code RequestUserInputValidationTest}. */
@WebMvcTest(UserReplyController.class)
@Import({SecurityConfiguration.class, UserReplyApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class UserReplyValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String VALID_BODY = "The laptop is connected to VPN and I attached the screenshot of the enrollment error.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserReplyAndResumeUseCase userReplyAndResumeUseCase;

    @MockitoBean
    private SecretDetectionPolicy secretDetectionPolicy;

    @MockitoBean
    private SecretDetectionAuditRecorder secretDetectionAuditRecorder;

    @MockitoBean
    private TicketTelemetry ticketTelemetry;

    private String route() {
        return "/api/v1/tickets/" + TICKET_ID + "/user-input-requests/" + REQUEST_ID + "/reply";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "alice").claim("actor_type", "EMPLOYEE").claim("scope", "tickets:message:self")))
            .header("If-Match", "\"21\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    private String bodyWith(String body) {
        return "{\"body\":\"" + body + "\"}";
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "alice").claim("actor_type", "EMPLOYEE").claim("scope", "tickets:message:self")))
                .header("If-Match", "\"21\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWith(VALID_BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(userReplyAndResumeUseCase, never()).reply(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "alice").claim("actor_type", "EMPLOYEE").claim("scope", "tickets:message:self")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWith(VALID_BODY)))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(userReplyAndResumeUseCase, never()).reply(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(bodyWith(VALID_BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400ForAMalformedTicketIdPathSegment() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/not-a-uuid/user-input-requests/" + REQUEST_ID + "/reply")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "alice").claim("actor_type", "EMPLOYEE").claim("scope", "tickets:message:self")))
                .header("If-Match", "\"21\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWith(VALID_BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400ForAMalformedRequestIdPathSegment() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/user-input-requests/not-a-uuid/reply")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "alice").claim("actor_type", "EMPLOYEE").claim("scope", "tickets:message:self")))
                .header("If-Match", "\"21\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWith(VALID_BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingBody() throws Exception {
        mockMvc.perform(validRequest().content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectABlankBody() throws Exception {
        mockMvc.perform(validRequest().content(bodyWith("   ")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectABodyOverEightThousandCharacters() throws Exception {
        mockMvc.perform(validRequest().content(bodyWith("a".repeat(8001))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMoreThanTwentyAttachmentIds() throws Exception {
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                ids.append(",");
            }
            ids.append("\"att-").append(i).append("\"");
        }
        ids.append("]");

        mockMvc.perform(validRequest().content("{\"body\":\"" + VALID_BODY + "\",\"attachmentIds\":" + ids + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields() throws Exception {
        mockMvc.perform(validRequest().content("{\"body\":\"" + VALID_BODY + "\",\"repliedBy\":\"someone-else\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(userReplyAndResumeUseCase, never()).reply(any());
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(validRequest().content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
