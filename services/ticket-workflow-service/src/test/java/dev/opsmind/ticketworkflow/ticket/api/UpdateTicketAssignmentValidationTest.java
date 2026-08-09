package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.UpdateTicketAssignmentApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.UpdateTicketAssignmentController;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UpdateTicketAssignmentUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-030 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code ReopenTicketValidationTest}. */
@WebMvcTest(UpdateTicketAssignmentController.class)
@Import({SecurityConfiguration.class, UpdateTicketAssignmentApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class UpdateTicketAssignmentValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID QUEUE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String VALID_BODY = """
        {"supportQueueId":"%s","assigneeId":"alex.support","reason":"Rebalancing queue load across teams."}
        """.formatted(QUEUE_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UpdateTicketAssignmentUseCase updateTicketAssignmentUseCase;

    private String route() {
        return "/api/v1/tickets/" + TICKET_ID + "/assignment";
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post(route())
            .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign-route")))
            .header("If-Match", "\"7\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign-route")))
                .header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post(route())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "lead.sam").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign-route")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldRejectAMissingSupportQueueId() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"assigneeId":"alex.support","reason":"Rebalancing queue load across teams."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldAllowAMissingAssigneeId() throws Exception {
        when(updateTicketAssignmentUseCase.updateAssignment(any())).thenReturn(new UpdateTicketAssignmentResult(
            TicketId.of(TICKET_ID), TicketStatus.IN_PROGRESS, "TEAM-B", SupportQueueId.of(QUEUE_ID), null, null,
            "Rebalancing queue load across teams.", "lead.sam", Instant.parse("2026-08-07T23:00:00Z"), 8L, false
        ));

        mockMvc.perform(validRequest().content("""
                {"supportQueueId":"%s","reason":"Rebalancing queue load across teams."}
                """.formatted(QUEUE_ID)))
            .andExpect(status().isOk());
    }

    @Test
    void shouldRejectABlankReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"supportQueueId":"%s","assigneeId":"alex.support","reason":"   "}
                """.formatted(QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldRejectATooShortReason() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"supportQueueId":"%s","assigneeId":"alex.support","reason":"ab"}
                """.formatted(QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldRejectATooLongReason() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"supportQueueId\":\"" + QUEUE_ID + "\",\"assigneeId\":\"alex.support\",\"reason\":\"" + "a".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldRejectAMalformedSupportQueueId() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"supportQueueId":"not-a-uuid","assigneeId":"alex.support","reason":"Rebalancing queue load across teams."}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }

    @Test
    void shouldRejectAnUnknownField() throws Exception {
        mockMvc.perform(validRequest().content("""
                {"supportQueueId":"%s","assigneeId":"alex.support","reason":"Rebalancing queue load across teams.","updatedBy":"someone-else"}
                """.formatted(QUEUE_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(updateTicketAssignmentUseCase, never()).updateAssignment(any());
    }
}
