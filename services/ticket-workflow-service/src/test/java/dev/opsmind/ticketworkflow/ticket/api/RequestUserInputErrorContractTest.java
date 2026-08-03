package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.RequestUserInputApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.RequestUserInputController;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.UserInputRequestAlreadyOpenException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.RequestUserInputUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-012's stable error codes, translated to this codebase's error envelope. Mirrors {@code CloseTicketErrorContractTest}. */
@WebMvcTest(RequestUserInputController.class)
@Import({SecurityConfiguration.class, RequestUserInputApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class RequestUserInputErrorContractTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String REQUEST_BODY = """
        {"prompt":"Please upload a screenshot of the error and confirm whether the laptop is connected to VPN."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RequestUserInputUseCase requestUserInputUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/user-input-requests")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-user-input")))
            .header("If-Match", "\"20\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST_BODY);
    }

    @Test
    void shouldReturn403Forbidden() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new TicketAuthorizationException("ticket:request-user-input"));

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn403QueueAccessDenied() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new QueueAccessDeniedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("QUEUE_ACCESS_DENIED"));
    }

    @Test
    void shouldReturn404TicketNotFound() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn409InvalidStatusTransition() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new InvalidStatusTransitionException(TicketStatus.ASSIGNED, TicketStatus.WAITING_FOR_USER));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
            .andExpect(jsonPath("$.error.details.currentStatus").value("ASSIGNED"))
            .andExpect(jsonPath("$.error.details.targetStatus").value("WAITING_FOR_USER"));
    }

    @Test
    void shouldReturn409UserInputRequestAlreadyOpen() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new UserInputRequestAlreadyOpenException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("USER_INPUT_REQUEST_ALREADY_OPEN"));
    }

    @Test
    void shouldReturn409IdempotencyKeyReused() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturn409RequestInProgressWithRetryAfterHeader() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new RequestInProgressException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void shouldReturn412VersionConflictWithCurrentVersionDetailAndETagHeader() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new TicketVersionConflictException(21L));

        mockMvc.perform(validRequest())
            .andExpect(status().is(412))
            .andExpect(header().string("ETag", "\"21\""))
            .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.details.currentVersion").value(21));
    }

    @Test
    void shouldReturn428PreconditionRequiredWhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/user-input-requests")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-user-input")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void shouldReturn201CreatedWithLocationAndETagOnSuccess() throws Exception {
        UUID requestId = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
        when(requestUserInputUseCase.requestUserInput(any())).thenReturn(new RequestUserInputResult(
            TicketId.of(TICKET_ID), requestId, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER,
            "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.", "sam.support",
            Instant.parse("2026-08-03T18:00:00Z"), Instant.parse("2026-08-03T18:00:00Z"), 21L, false
        ));

        mockMvc.perform(validRequest())
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"21\""))
            .andExpect(header().string("Location", "/api/v1/tickets/" + TICKET_ID + "/user-input-requests/" + requestId))
            .andExpect(jsonPath("$.status").value("WAITING_FOR_USER"));
    }

    @Test
    void everyErrorBodyShouldExposeTheSharedEnvelopeFieldsNotRfc9457Fields() throws Exception {
        when(requestUserInputUseCase.requestUserInput(any())).thenThrow(new TicketNotFoundException());

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
