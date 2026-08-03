package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.TicketAssignmentApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.TicketAssignmentController;
import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.command.UnassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AssignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ReassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UnassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-TW-008 API contract §5: the {@code 200} response shape, {@code
 * ETag}, and the actor/team claims each of the three routes passes through.
 * Mirrors {@code TriageTicketControllerTest}'s structure.
 */
@WebMvcTest(TicketAssignmentController.class)
@Import({SecurityConfiguration.class, TicketAssignmentApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketAssignmentControllerTest {

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

    @Test
    void shouldReturn200WithETagAndTheSuccessBodyShapeForAssign() throws Exception {
        when(assignTicketUseCase.assign(any())).thenReturn(new TicketAssignmentResult(
            TicketId.of(TICKET_ID), TicketStatus.ASSIGNED, "agent-1", "Sam Lee", Instant.parse("2026-07-31T18:30:00Z"), 8L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/assign")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                    .claim("scope", "ticket:assign").claim("support_teams", List.of("TEAM-HOUSING"))))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ASSIGN_BODY))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"8\""))
            .andExpect(jsonPath("$.ticketId").value(TICKET_ID.toString()))
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.assignee.id").value("agent-1"))
            .andExpect(jsonPath("$.assignee.displayName").value("Sam Lee"))
            .andExpect(jsonPath("$.assignedAt").exists())
            .andExpect(jsonPath("$.version").value(8));

        ArgumentCaptor<AssignTicketCommand> captor = ArgumentCaptor.forClass(AssignTicketCommand.class);
        verify(assignTicketUseCase).assign(captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(7L);
        assertThat(captor.getValue().allowedTeamIds()).containsExactly("TEAM-HOUSING");
        assertThat(captor.getValue().actor().subject()).isEqualTo("support-100");
        assertThat(captor.getValue().actor().actorType()).isEqualTo("IT_SUPPORT");
        assertThat(captor.getValue().assigneeId()).isEqualTo("agent-1");
        assertThat(captor.getValue().reason()).isEqualTo("Primary endpoint support owner");
    }

    @Test
    void shouldReturn200WithETagAndTheSuccessBodyShapeForReassign() throws Exception {
        when(reassignTicketUseCase.reassign(any())).thenReturn(new TicketAssignmentResult(
            TicketId.of(TICKET_ID), TicketStatus.ASSIGNED, "agent-2", "Jordan Cole", Instant.parse("2026-07-31T18:30:00Z"), 9L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/reassign")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                    .claim("scope", "ticket:assign").claim("support_teams", List.of("TEAM-HOUSING"))))
                .header("If-Match", "\"8\"")
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assigneeId":"agent-2","reason":"Escalated to network specialist"}
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"9\""))
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.assignee.id").value("agent-2"))
            .andExpect(jsonPath("$.assignee.displayName").value("Jordan Cole"))
            .andExpect(jsonPath("$.version").value(9));

        ArgumentCaptor<ReassignTicketCommand> captor = ArgumentCaptor.forClass(ReassignTicketCommand.class);
        verify(reassignTicketUseCase).reassign(captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(8L);
        assertThat(captor.getValue().assigneeId()).isEqualTo("agent-2");
    }

    @Test
    void shouldReturn200WithNullAssigneeAndAssignedAtLiteralsForUnassign() throws Exception {
        when(unassignTicketUseCase.unassign(any())).thenReturn(new TicketAssignmentResult(
            TicketId.of(TICKET_ID), TicketStatus.TRIAGED, null, null, null, 10L, false
        ));

        String body = mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/unassign")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                    .claim("scope", "ticket:assign").claim("support_teams", List.of("TEAM-HOUSING"))))
                .header("If-Match", "\"9\"")
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(UNASSIGN_BODY))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"10\""))
            .andExpect(jsonPath("$.status").value("TRIAGED"))
            .andExpect(jsonPath("$.version").value(10))
            .andReturn().getResponse().getContentAsString();

        // API contract §5: for unassign, assignee/assignedAt must be JSON null LITERALS (keys present,
        // value null) rather than absent keys — parse into a tree and assert the keys exist with a
        // JSON null node, which is the only assertion that actually distinguishes "present but null"
        // from "absent" (a naive jsonPath null-check would pass for either).
        com.fasterxml.jackson.databind.JsonNode tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(tree.has("assignee")).isTrue();
        assertThat(tree.get("assignee").isNull()).isTrue();
        assertThat(tree.has("assignedAt")).isTrue();
        assertThat(tree.get("assignedAt").isNull()).isTrue();

        ArgumentCaptor<UnassignTicketCommand> captor = ArgumentCaptor.forClass(UnassignTicketCommand.class);
        verify(unassignTicketUseCase).unassign(captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(9L);
        assertThat(captor.getValue().reason()).isEqualTo("Agent left the support rotation");
    }

    @Test
    void shouldExtractExpectedVersionFromAnUnquotedIfMatchHeader() throws Exception {
        when(assignTicketUseCase.assign(any())).thenReturn(new TicketAssignmentResult(
            TicketId.of(TICKET_ID), TicketStatus.ASSIGNED, "agent-1", "Sam Lee", Instant.parse("2026-07-31T18:30:00Z"), 8L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/assign")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:assign")))
                .header("If-Match", "7")
                .header("Idempotency-Key", "key-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ASSIGN_BODY))
            .andExpect(status().isOk());

        ArgumentCaptor<AssignTicketCommand> captor = ArgumentCaptor.forClass(AssignTicketCommand.class);
        verify(assignTicketUseCase).assign(captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(7L);
    }
}
