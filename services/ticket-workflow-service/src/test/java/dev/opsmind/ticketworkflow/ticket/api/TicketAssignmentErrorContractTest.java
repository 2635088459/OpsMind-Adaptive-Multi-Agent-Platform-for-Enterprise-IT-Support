package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.TicketAssignmentApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.TicketAssignmentController;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeInactiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotInQueueException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotSupportAgentException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AssignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ReassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UnassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.ReassignmentRequiresDifferentAssigneeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketAlreadyAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
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

/**
 * SPEC-TW-008 §8's stable error codes plus the reconciliation deviations,
 * translated to this codebase's ACTUAL error envelope ({@code
 * {"error":{"code","message","traceId","correlationId","details"}}}, not
 * the spec's literal RFC-9457 fields). Mirrors {@code
 * TriageTicketErrorContractTest}'s structure; every scenario is exercised
 * through the {@code assign} route since all three share one exception
 * handler chain and one controller class.
 */
@WebMvcTest(TicketAssignmentController.class)
@Import({SecurityConfiguration.class, TicketAssignmentApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketAssignmentErrorContractTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String ASSIGN_BODY = """
        {"assigneeId":"agent-1","reason":"A valid reason for the ownership change."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssignTicketUseCase assignTicketUseCase;

    @MockitoBean
    private ReassignTicketUseCase reassignTicketUseCase;

    @MockitoBean
    private UnassignTicketUseCase unassignTicketUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/assign")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
            .header("If-Match", "\"7\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(ASSIGN_BODY);
    }

    @Test
    void shouldReturn400ValidationErrorForReassignmentRequiresDifferentAssignee() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new ReassignmentRequiresDifferentAssigneeException());

        mockMvc.perform(validRequest())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn403Forbidden() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new TicketAuthorizationException("ticket:assign"));

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn403QueueAccessDenied() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new QueueAccessDeniedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("QUEUE_ACCESS_DENIED"));
    }

    @Test
    void shouldReturn404TicketNotFound() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn404AssigneeNotFound() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new AssigneeNotFoundException());

        mockMvc.perform(validRequest())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ASSIGNEE_NOT_FOUND"));
    }

    @Test
    void shouldReturn409InvalidTicketStateWithTheSingleStatusShapeForAssign() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new InvalidTicketTransitionException(TicketStatus.NEW, TicketStatus.TRIAGED));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_TICKET_STATE"))
            .andExpect(jsonPath("$.error.details.currentStatus").value("NEW"))
            .andExpect(jsonPath("$.error.details.requiredStatus").value("TRIAGED"));
    }

    @Test
    void shouldReturn409InvalidTicketStateWithTheMultiStatusShapeForReassign() throws Exception {
        when(assignTicketUseCase.assign(any()))
            .thenThrow(new InvalidTicketStateException(TicketStatus.TRIAGED, Ticket.REASSIGNABLE_STATUSES));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_TICKET_STATE"))
            .andExpect(jsonPath("$.error.details.currentStatus").value("TRIAGED"))
            .andExpect(jsonPath("$.error.details.allowedStatuses").isArray())
            .andExpect(jsonPath("$.error.details.allowedStatuses", org.hamcrest.Matchers.containsInAnyOrder("ASSIGNED", "IN_PROGRESS", "WAITING_FOR_USER", "WAITING_FOR_APPROVAL")));
    }

    @Test
    void shouldReturn409TicketAlreadyAssigned() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new TicketAlreadyAssignedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_ASSIGNED"));
    }

    @Test
    void shouldReturn409TicketNotAssigned() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new TicketNotAssignedException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_ASSIGNED"));
    }

    @Test
    void shouldReturn409AssigneeInactive() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new AssigneeInactiveException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSIGNEE_INACTIVE"));
    }

    @Test
    void shouldReturn409AssigneeNotSupportAgent() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new AssigneeNotSupportAgentException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSIGNEE_NOT_SUPPORT_AGENT"));
    }

    @Test
    void shouldReturn409AssigneeNotInQueue() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new AssigneeNotInQueueException());

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSIGNEE_NOT_IN_QUEUE"));
    }

    @Test
    void shouldReturn409IdempotencyKeyReused() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturn409RequestInProgressWithRetryAfterHeader() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new RequestInProgressException("key-1"));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void shouldReturn412VersionConflictWithCurrentVersionDetailAndETagHeader() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new TicketVersionConflictException(9L));

        mockMvc.perform(validRequest())
            .andExpect(status().is(412))
            .andExpect(header().string("ETag", "\"9\""))
            .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.details.currentVersion").value(9));
    }

    @Test
    void shouldReturn428PreconditionRequiredWhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/assign")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ASSIGN_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/assign")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void everyErrorBodyShouldExposeTheSharedEnvelopeFieldsNotRfc9457Fields() throws Exception {
        when(assignTicketUseCase.assign(any())).thenThrow(new TicketNotFoundException());

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
