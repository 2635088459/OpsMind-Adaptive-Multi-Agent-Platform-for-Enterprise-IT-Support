package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.ResolveTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.ResolveTicketController;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleAlreadyCompletedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResolveTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
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

/** SPEC-TW-010 §8's stable error codes, translated to this codebase's error envelope. Mirrors {@code TicketAssignmentErrorContractTest}. */
@WebMvcTest(ResolveTicketController.class)
@Import({SecurityConfiguration.class, ResolveTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class ResolveTicketErrorContractTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String RESOLVE_BODY = """
        {"resolutionCode":"FIXED","resolutionSummary":"Reinstalled the endpoint management profile and confirmed check-in."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolveTicketUseCase resolveTicketUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/resolution")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:resolve")))
            .header("If-Match", "\"17\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(RESOLVE_BODY);
    }

    @Test
    void shouldReturn403Forbidden() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new TicketAuthorizationException("ticket:resolve"));

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn403QueueAccessDenied() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new QueueAccessDeniedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("QUEUE_ACCESS_DENIED"));
    }

    @Test
    void shouldReturn404TicketNotFound() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn409InvalidStatusTransition() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new InvalidStatusTransitionException(TicketStatus.ASSIGNED, TicketStatus.RESOLVED));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
            .andExpect(jsonPath("$.error.details.currentStatus").value("ASSIGNED"))
            .andExpect(jsonPath("$.error.details.targetStatus").value("RESOLVED"));
    }

    @Test
    void shouldReturn409TicketNotAssigned() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new TicketNotAssignedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_ASSIGNED"));
    }

    @Test
    void shouldReturn409ResolutionCycleNotFound() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new ResolutionCycleNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("RESOLUTION_CYCLE_NOT_FOUND"));
    }

    @Test
    void shouldReturn409ResolutionCycleAlreadyCompleted() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new ResolutionCycleAlreadyCompletedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("RESOLUTION_CYCLE_ALREADY_COMPLETED"));
    }

    @Test
    void shouldReturn409IdempotencyKeyReused() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturn409RequestInProgressWithRetryAfterHeader() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new RequestInProgressException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void shouldReturn412VersionConflictWithCurrentVersionDetailAndETagHeader() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new TicketVersionConflictException(18L));

        mockMvc.perform(validRequest())
            .andExpect(status().is(412))
            .andExpect(header().string("ETag", "\"18\""))
            .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.details.currentVersion").value(18));
    }

    @Test
    void shouldReturn428PreconditionRequiredWhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/resolution")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:resolve")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RESOLVE_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void everyErrorBodyShouldExposeTheSharedEnvelopeFieldsNotRfc9457Fields() throws Exception {
        when(resolveTicketUseCase.resolve(any())).thenThrow(new TicketNotFoundException());

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
