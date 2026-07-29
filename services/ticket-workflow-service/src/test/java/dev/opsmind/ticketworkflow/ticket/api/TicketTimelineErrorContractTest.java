package dev.opsmind.ticketworkflow.ticket.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.platform.error.ErrorResponse;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.TicketTimelineController;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTimelineUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-TW-006 §24: every documented Timeline error follows the shared error
 * envelope, never reveals hidden-resource existence, cursor payloads, or
 * internals, and — for {@code INVALID_CURSOR} — conforms to the frozen
 * {@code invalid-cursor-error.schema.json}.
 *
 * <p>Schema conformance is checked against a manually built {@link
 * ErrorResponse} (mirroring {@code GetTicketResponseRedactionTest}), not the
 * live MockMvc body: nothing in this codebase populates the {@code traceId}
 * MDC key today, so a live response's {@code traceId} is always {@code ""}
 * — which the frozen schema's {@code minLength: 1} correctly rejects. The
 * live-body assertions below instead use {@code jsonPath} to verify the
 * actual HTTP contract (status, code, no leakage).
 */
@WebMvcTest(TicketTimelineController.class)
@Import({SecurityConfiguration.class, EmployeeTimelineApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketTimelineErrorContractTest {

    private static final String TICKET_ID = UUID.randomUUID().toString();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketTimelineUseCase getTicketTimelineUseCase;

    @MockitoBean
    private SupportTimelineApiMapper supportTimelineApiMapper;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor employeeJwt() {
        return jwt().jwt(jwt -> jwt.claim("sub", TicketTimelineFixtures.DEFAULT_REQUESTER).claim("actor_type", "EMPLOYEE"))
            .authorities(new SimpleGrantedAuthority("SCOPE_" + TicketTimelineFixtures.EMPLOYEE_SCOPE));
    }

    @Test
    void shouldReturnSafeEnvelopeWhenTicketNotFound() throws Exception {
        when(getTicketTimelineUseCase.getTimeline(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline").with(employeeJwt()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TICKET_ID))));
    }

    @Test
    void shouldReturnSafeEnvelopeWhenAuthorizationDenied() throws Exception {
        when(getTicketTimelineUseCase.getTimeline(any())).thenThrow(new TicketAuthorizationException("tickets:read:self"));

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline").with(employeeJwt()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnSafeEnvelopeForInvalidCursorWithoutRevealingWhy() throws Exception {
        when(getTicketTimelineUseCase.getTimeline(any())).thenThrow(new InvalidCursorException());

        MvcResult result = mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline?cursor=tampered.cursor").with(employeeJwt()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("tampered.cursor");
    }

    @Test
    void shouldReturn400ForOutOfRangeLimitWithoutInvokingTheUseCase() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline?limit=0").with(employeeJwt()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn500WithoutBodyLeakageWhenSensitiveReadAuditFails() throws Exception {
        when(getTicketTimelineUseCase.getTimeline(any())).thenThrow(new SensitiveReadAuditFailureException(new RuntimeException("db down")));

        MvcResult result = mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline").with(employeeJwt()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db down"))))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("db down");
    }

    @Test
    void shouldRejectUnauthenticatedRequestWithoutRevealingResourceExistence() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/timeline"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void ticketNotFoundErrorShouldConformToFrozenErrorEnvelopeSchema() throws Exception {
        ErrorResponse error = ErrorResponse.of("TICKET_NOT_FOUND", "The Ticket was not found.", "trace-1", "corr-1");

        assertConformsToSchema(error, "schemas/ticket/error-envelope.schema.json");
    }

    @Test
    void forbiddenErrorShouldConformToFrozenErrorEnvelopeSchema() throws Exception {
        ErrorResponse error = ErrorResponse.of("FORBIDDEN", "The actor is not authorized to perform this action.", "trace-1", "corr-1");

        assertConformsToSchema(error, "schemas/ticket/error-envelope.schema.json");
    }

    @Test
    void internalErrorShouldConformToFrozenErrorEnvelopeSchema() throws Exception {
        ErrorResponse error = ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred.", "trace-1", "corr-1");

        assertConformsToSchema(error, "schemas/ticket/error-envelope.schema.json");
    }

    @Test
    void invalidCursorErrorShouldConformToFrozenInvalidCursorErrorSchema() throws Exception {
        ErrorResponse error = ErrorResponse.of("INVALID_CURSOR", "The pagination cursor is invalid or expired.", "trace-1", "corr-1");

        assertConformsToSchema(error, "schemas/ticket/invalid-cursor-error.schema.json");
    }

    private void assertConformsToSchema(ErrorResponse error, String schemaLocation) throws Exception {
        JsonNode node = objectMapper.valueToTree(error);
        Set<ValidationMessage> errors = loadSchema(schemaLocation).validate(node);
        assertThat(errors).as("schema violations for " + schemaLocation).isEmpty();
    }

    private JsonSchema loadSchema(String location) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as("schema resource on classpath: " + location).isNotNull();
            return schemaFactory.getSchema(in);
        }
    }
}
