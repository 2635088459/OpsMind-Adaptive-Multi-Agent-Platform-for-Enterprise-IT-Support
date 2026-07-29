package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.TriageTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.TriageTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.TriageTicketUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/**
 * SPEC-TW-007 AC-05/AC-10/AC-13: header presence/shape, path parameter
 * shape, request-body Bean Validation, and unknown-property rejection.
 * Mirrors {@code AddTicketMessageValidationTest}/{@code
 * AddTicketMessageMassAssignmentTest}'s structure.
 */
@WebMvcTest(TriageTicketController.class)
@Import({SecurityConfiguration.class, TriageTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TriageTicketValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CATEGORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUBCATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID QUEUE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String VALID_BODY = """
        {"categoryId":"%s","subcategoryId":"%s","priority":"HIGH","supportQueueId":"%s","reason":"VPN access failure affects the requester's scheduled shift."}
        """.formatted(CATEGORY_ID, SUBCATEGORY_ID, QUEUE_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TriageTicketUseCase triageTicketUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/triage")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
            .header("If-Match", "\"7\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(triageTicketUseCase, never()).triage(any());
    }

    @Test
    void shouldRejectBlankIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAnIdempotencyKeyOver128Characters() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "k".repeat(129))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(triageTicketUseCase, never()).triage(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("If-Match", "   ")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("If-Match", "not-a-number")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(triageTicketUseCase, never()).triage(any());
    }

    @Test
    void shouldReturn400ForAMalformedTicketIdPathSegment() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/not-a-uuid/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:triage")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMissingCategoryId() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"priority":"HIGH","supportQueueId":"%s","reason":"A valid reason."}
                """.formatted(QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMissingPriority() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"categoryId":"%s","supportQueueId":"%s","reason":"A valid reason."}
                """.formatted(CATEGORY_ID, QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMissingSupportQueueId() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"categoryId":"%s","priority":"HIGH","reason":"A valid reason."}
                """.formatted(CATEGORY_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMissingReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"categoryId":"%s","priority":"HIGH","supportQueueId":"%s"}
                """.formatted(CATEGORY_ID, QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAnEmptyReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"categoryId":"%s","priority":"HIGH","supportQueueId":"%s","reason":""}
                """.formatted(CATEGORY_ID, QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAReasonOver500Characters() throws Exception {
        String tooLong = "a".repeat(501);
        mockMvc.perform(validRequest().content("{\"categoryId\":\"" + CATEGORY_ID + "\",\"priority\":\"HIGH\",\"supportQueueId\":\""
                + QUEUE_ID + "\",\"reason\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"URGENT", "unassigned", "UNASSIGNED", "not-a-priority"})
    void shouldRejectAnInvalidPriorityValue(String priority) throws Exception {
        mockMvc.perform(validRequest().content("""
                {"categoryId":"%s","priority":"%s","supportQueueId":"%s","reason":"A valid reason."}
                """.formatted(CATEGORY_ID, priority, QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(triageTicketUseCase, never()).triage(any());
    }

    /** AC-13: the request never accepts triagedBy/triagedAt/status/version; any such field is rejected as an unknown property. */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"categoryId\":\"11111111-1111-1111-1111-111111111111\",\"priority\":\"HIGH\",\"supportQueueId\":\"33333333-3333-3333-3333-333333333333\",\"reason\":\"A valid reason.\",\"triagedBy\":\"x\"}",
        "{\"categoryId\":\"11111111-1111-1111-1111-111111111111\",\"priority\":\"HIGH\",\"supportQueueId\":\"33333333-3333-3333-3333-333333333333\",\"reason\":\"A valid reason.\",\"triagedAt\":\"2020-01-01T00:00:00Z\"}",
        "{\"categoryId\":\"11111111-1111-1111-1111-111111111111\",\"priority\":\"HIGH\",\"supportQueueId\":\"33333333-3333-3333-3333-333333333333\",\"reason\":\"A valid reason.\",\"status\":\"TRIAGED\"}",
        "{\"categoryId\":\"11111111-1111-1111-1111-111111111111\",\"priority\":\"HIGH\",\"supportQueueId\":\"33333333-3333-3333-3333-333333333333\",\"reason\":\"A valid reason.\",\"version\":5}"
    })
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields(String body) throws Exception {
        mockMvc.perform(validRequest().content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(triageTicketUseCase, never()).triage(any());
    }
}
