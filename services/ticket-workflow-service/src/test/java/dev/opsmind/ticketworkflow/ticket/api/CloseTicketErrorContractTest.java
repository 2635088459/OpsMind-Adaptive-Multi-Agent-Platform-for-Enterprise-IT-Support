package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.CloseTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.CloseTicketController;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CloseTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-011 §8's stable error codes, translated to this codebase's error envelope. Mirrors {@code ResolveTicketErrorContractTest}. */
@WebMvcTest(CloseTicketController.class)
@Import({SecurityConfiguration.class, CloseTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class CloseTicketErrorContractTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String CLOSE_BODY = """
        {"closeReasonCode":"REQUESTER_CONFIRMED","closeReason":"Requester confirmed the issue is resolved and no further action is required."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CloseTicketUseCase closeTicketUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/closure")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:close")))
            .header("If-Match", "\"18\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CLOSE_BODY);
    }

    @Test
    void shouldReturn403Forbidden() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new TicketAuthorizationException("ticket:close"));

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn403QueueAccessDenied() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new QueueAccessDeniedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("QUEUE_ACCESS_DENIED"));
    }

    @Test
    void shouldReturn404TicketNotFound() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn409InvalidStatusTransition() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new InvalidStatusTransitionException(TicketStatus.IN_PROGRESS, TicketStatus.CLOSED));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
            .andExpect(jsonPath("$.error.details.currentStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.error.details.targetStatus").value("CLOSED"));
    }

    @Test
    void shouldReturn409ResolutionCycleNotFound() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new ResolutionCycleNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("RESOLUTION_CYCLE_NOT_FOUND"));
    }

    @Test
    void shouldReturn409IdempotencyKeyReused() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturn409RequestInProgressWithRetryAfterHeader() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new RequestInProgressException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void shouldReturn412VersionConflictWithCurrentVersionDetailAndETagHeader() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new TicketVersionConflictException(19L));

        mockMvc.perform(validRequest())
            .andExpect(status().is(412))
            .andExpect(header().string("ETag", "\"19\""))
            .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.details.currentVersion").value(19));
    }

    @Test
    void shouldReturn428PreconditionRequiredWhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/closure")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:close")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CLOSE_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void everyErrorBodyShouldExposeTheSharedEnvelopeFieldsNotRfc9457Fields() throws Exception {
        when(closeTicketUseCase.close(any())).thenThrow(new TicketNotFoundException());

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
