package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.TicketAssignmentApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.TicketAssignmentController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AssignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ReassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UnassignTicketUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-TW-008 API contract §7/§8: header presence/shape, path parameter
 * shape, request-body Bean Validation, and unknown-property rejection for
 * all three routes. Mirrors {@code TriageTicketValidationTest}'s structure.
 */
@WebMvcTest(TicketAssignmentController.class)
@Import({SecurityConfiguration.class, TicketAssignmentApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketAssignmentValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String ASSIGN_BODY = """
        {"assigneeId":"agent-1","reason":"Primary endpoint support owner"}
        """;
    private static final String UNASSIGN_BODY = """
        {"reason":"Agent left the support rotation"}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssignTicketUseCase assignTicketUseCase;

    @MockitoBean
    private ReassignTicketUseCase reassignTicketUseCase;

    @MockitoBean
    private UnassignTicketUseCase unassignTicketUseCase;

    private static Stream<String> routesWithAssigneeBody() {
        return Stream.of("assign", "reassign");
    }

    private static Stream<String> allRoutes() {
        return Stream.of("assign", "reassign", "unassign");
    }

    private String bodyFor(String route) {
        return "unassign".equals(route) ? UNASSIGN_BODY : ASSIGN_BODY;
    }

    private MockHttpServletRequestBuilder validRequest(String route) {
        return post("/api/v1/tickets/" + TICKET_ID + "/" + route)
            .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
            .header("If-Match", "\"7\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    private void verifyNoUseCaseInvoked() throws Exception {
        verify(assignTicketUseCase, never()).assign(any());
        verify(reassignTicketUseCase, never()).reassign(any());
        verify(unassignTicketUseCase, never()).unassign(any());
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldRejectMissingIdempotencyKey(String route) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/" + route)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(route)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoUseCaseInvoked();
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldRejectBlankIdempotencyKey(String route) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/" + route)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(route)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldRejectAnIdempotencyKeyOver128Characters(String route) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/" + route)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "k".repeat(129))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(route)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldReturn428WhenIfMatchIsMissing(String route) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/" + route)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(route)))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verifyNoUseCaseInvoked();
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldReturn428WhenIfMatchIsBlank(String route) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/" + route)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "   ")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(route)))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldReturn400WhenIfMatchIsNotANumber(String route) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/" + route)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "not-a-number")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(route)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoUseCaseInvoked();
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldReturn400ForAMalformedTicketIdPathSegment(String route) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/not-a-uuid/" + route)
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(route)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldRejectMissingReason(String route) throws Exception {
        String body = "unassign".equals(route) ? "{}" : "{\"assigneeId\":\"agent-1\"}";
        mockMvc.perform(validRequest(route).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldRejectAReasonUnderThreeCharacters(String route) throws Exception {
        String body = "unassign".equals(route) ? "{\"reason\":\"ab\"}" : "{\"assigneeId\":\"agent-1\",\"reason\":\"ab\"}";
        mockMvc.perform(validRequest(route).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldRejectAReasonOver500Characters(String route) throws Exception {
        String tooLong = "a".repeat(501);
        String body = "unassign".equals(route)
            ? "{\"reason\":\"" + tooLong + "\"}"
            : "{\"assigneeId\":\"agent-1\",\"reason\":\"" + tooLong + "\"}";
        mockMvc.perform(validRequest(route).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("routesWithAssigneeBody")
    void shouldRejectMissingAssigneeIdOnAssignAndReassign(String route) throws Exception {
        mockMvc.perform(validRequest(route).content("""
                {"reason":"A valid reason for the ownership change."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("routesWithAssigneeBody")
    void shouldRejectBlankAssigneeIdOnAssignAndReassign(String route) throws Exception {
        mockMvc.perform(validRequest(route).content("""
                {"assigneeId":"   ","reason":"A valid reason for the ownership change."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** API contract §8: {@code assigneeId} is forbidden for unassign; {@code UnassignTicketRequest} declares no such field. */
    @Test
    void shouldRejectAssigneeIdPresentInAnUnassignRequestBody() throws Exception {
        mockMvc.perform(validRequest("unassign").content("""
                {"assigneeId":"agent-1","reason":"Agent left the support rotation"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(unassignTicketUseCase, never()).unassign(any());
    }

    @ParameterizedTest
    @MethodSource("allRoutes")
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields(String route) throws Exception {
        String body = "unassign".equals(route)
            ? "{\"reason\":\"A valid reason for the ownership change.\",\"status\":\"ASSIGNED\"}"
            : "{\"assigneeId\":\"agent-1\",\"reason\":\"A valid reason for the ownership change.\",\"status\":\"ASSIGNED\"}";
        mockMvc.perform(validRequest(route).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoUseCaseInvoked();
    }

    @ParameterizedTest
    @ValueSource(strings = {"assign", "reassign", "unassign"})
    void shouldReturn400ValidationErrorForAMalformedBody(String route) throws Exception {
        mockMvc.perform(validRequest(route).content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
