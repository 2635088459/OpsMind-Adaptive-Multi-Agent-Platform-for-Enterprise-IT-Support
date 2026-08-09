package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.UserReplyApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.UserReplyController;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UserReplyAndResumeUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-013's stable error codes, translated to this codebase's error envelope. Mirrors {@code RequestUserInputErrorContractTest}. */
@WebMvcTest(UserReplyController.class)
@Import({SecurityConfiguration.class, UserReplyApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class UserReplyErrorContractTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String REPLY_BODY = """
        {"body":"The laptop is connected to VPN and I attached the screenshot of the enrollment error."}
        """;

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
            .contentType(MediaType.APPLICATION_JSON)
            .content(REPLY_BODY);
    }

    @Test
    void shouldReturn403Forbidden() throws Exception {
        when(userReplyAndResumeUseCase.reply(any())).thenThrow(new TicketAuthorizationException("tickets:message:self"));

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn404TicketNotFound() throws Exception {
        when(userReplyAndResumeUseCase.reply(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn409InvalidStatusTransition() throws Exception {
        when(userReplyAndResumeUseCase.reply(any())).thenThrow(new InvalidStatusTransitionException(TicketStatus.WAITING_FOR_USER, TicketStatus.IN_PROGRESS));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void shouldReturn409IdempotencyKeyReused() throws Exception {
        when(userReplyAndResumeUseCase.reply(any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturn409RequestInProgressWithRetryAfterHeader() throws Exception {
        when(userReplyAndResumeUseCase.reply(any())).thenThrow(new RequestInProgressException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void shouldReturn412VersionConflictWithCurrentVersionDetailAndETagHeader() throws Exception {
        when(userReplyAndResumeUseCase.reply(any())).thenThrow(new TicketVersionConflictException(22L));

        mockMvc.perform(validRequest())
            .andExpect(status().is(412))
            .andExpect(header().string("ETag", "\"22\""))
            .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.details.currentVersion").value(22));
    }

    @Test
    void shouldReturn428PreconditionRequiredWhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "alice").claim("actor_type", "EMPLOYEE").claim("scope", "tickets:message:self")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REPLY_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void everyErrorBodyShouldExposeTheSharedEnvelopeFieldsNotRfc9457Fields() throws Exception {
        when(userReplyAndResumeUseCase.reply(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(jsonPath("$.error.code").exists())
            .andExpect(jsonPath("$.error.message").exists())
            .andExpect(jsonPath("$.error.traceId").exists())
            .andExpect(jsonPath("$.error.correlationId").exists())
            .andExpect(jsonPath("$.type").doesNotExist())
            .andExpect(jsonPath("$.title").doesNotExist())
            .andExpect(jsonPath("$.instance").doesNotExist());
    }
}
