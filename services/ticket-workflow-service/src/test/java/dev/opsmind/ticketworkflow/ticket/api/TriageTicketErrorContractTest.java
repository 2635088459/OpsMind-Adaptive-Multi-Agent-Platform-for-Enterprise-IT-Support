package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.TriageTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.TriageTicketController;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageCategoryInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageSubcategoryInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.TriageTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-TW-007 api-contract "Stable Errors" table, translated to this
 * codebase's ACTUAL error envelope ({@code {"error":{"code","message",
 * "traceId","correlationId","details"}}}, not the spec's literal RFC-9457
 * fields — deviation #7).
 */
@WebMvcTest(TriageTicketController.class)
@Import({SecurityConfiguration.class, TriageTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TriageTicketErrorContractTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"categoryId":"11111111-1111-1111-1111-111111111111","priority":"HIGH",\
        "supportQueueId":"33333333-3333-3333-3333-333333333333","reason":"A valid reason."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TriageTicketUseCase triageTicketUseCase;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/triage")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
            .header("If-Match", "\"7\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY);
    }

    @Test
    void shouldReturn403TriageNotAllowed() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new TriageNotAllowedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("TRIAGE_NOT_ALLOWED"));
    }

    @Test
    void shouldReturn403QueueAccessDenied() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new QueueAccessDeniedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("QUEUE_ACCESS_DENIED"));
    }

    @Test
    void shouldReturn404TicketNotFound() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn409InvalidTicketStateWithCurrentAndRequiredStatusDetails() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new InvalidTicketTransitionException(TicketStatus.TRIAGED, TicketStatus.NEW));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_TICKET_STATE"))
            .andExpect(jsonPath("$.error.details.currentStatus").value("TRIAGED"))
            .andExpect(jsonPath("$.error.details.requiredStatus").value("NEW"));
    }

    @Test
    void shouldReturn409IdempotencyKeyReused() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturn412VersionConflictWithCurrentVersionDetailAndETagHeader() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new TicketVersionConflictException(9L));

        mockMvc.perform(validRequest())
            .andExpect(status().is(412))
            .andExpect(header().string("ETag", "\"9\""))
            .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.details.currentVersion").value(9));
    }

    @Test
    void shouldReturn422TriageCategoryInvalid() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new TriageCategoryInvalidException());

        mockMvc.perform(validRequest())
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("TRIAGE_CATEGORY_INVALID"));
    }

    @Test
    void shouldReturn422TriageSubcategoryInvalid() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new TriageSubcategoryInvalidException());

        mockMvc.perform(validRequest())
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("TRIAGE_SUBCATEGORY_INVALID"));
    }

    @Test
    void shouldReturn422SupportQueueInvalid() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new SupportQueueInvalidException());

        mockMvc.perform(validRequest())
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("SUPPORT_QUEUE_INVALID"));
    }

    @Test
    void shouldReturn428PreconditionRequiredWhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void everyErrorBodyShouldExposeTheSharedEnvelopeFields() throws Exception {
        when(triageTicketUseCase.triage(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(jsonPath("$.error.code").exists())
            .andExpect(jsonPath("$.error.message").exists())
            .andExpect(jsonPath("$.error.traceId").exists())
            .andExpect(jsonPath("$.error.correlationId").exists());
    }
}
