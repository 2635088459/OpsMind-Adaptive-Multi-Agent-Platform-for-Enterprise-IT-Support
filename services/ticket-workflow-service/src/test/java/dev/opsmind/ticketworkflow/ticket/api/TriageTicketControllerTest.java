package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.TriageTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.TriageTicketController;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.TriageTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;
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

/** SPEC-TW-007 AC-01: {@code 200} response shape, {@code ETag}, and the actor/team claims the controller passes through. */
@WebMvcTest(TriageTicketController.class)
@Import({SecurityConfiguration.class, TriageTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class TriageTicketControllerTest {

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

    @Test
    void shouldReturn200WithETagAndTheSuccessBodyShape() throws Exception {
        when(triageTicketUseCase.triage(any())).thenReturn(new TriageTicketResult(
            TicketId.of(TICKET_ID), TicketStatus.TRIAGED, TicketCategoryId.of(CATEGORY_ID), TicketSubcategoryId.of(SUBCATEGORY_ID),
            TicketPriority.HIGH, SupportQueueId.of(QUEUE_ID), "support-100", Instant.parse("2026-07-29T18:30:00Z"), 8L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                    .claim("scope", "ticket:triage").claim("support_teams", List.of("TEAM-HOUSING"))))
                .header("If-Match", "\"7\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"8\""))
            .andExpect(jsonPath("$.ticketId").value(TICKET_ID.toString()))
            .andExpect(jsonPath("$.status").value("TRIAGED"))
            .andExpect(jsonPath("$.categoryId").value(CATEGORY_ID.toString()))
            .andExpect(jsonPath("$.subcategoryId").value(SUBCATEGORY_ID.toString()))
            .andExpect(jsonPath("$.priority").value("HIGH"))
            .andExpect(jsonPath("$.supportQueueId").value(QUEUE_ID.toString()))
            .andExpect(jsonPath("$.triagedBy").value("support-100"))
            .andExpect(jsonPath("$.triagedAt").exists())
            .andExpect(jsonPath("$.version").value(8));
    }

    @Test
    void shouldReturnNullSubcategoryFieldWhenResultHasNone() throws Exception {
        when(triageTicketUseCase.triage(any())).thenReturn(new TriageTicketResult(
            TicketId.of(TICKET_ID), TicketStatus.TRIAGED, TicketCategoryId.of(CATEGORY_ID), null,
            TicketPriority.LOW, SupportQueueId.of(QUEUE_ID), "support-100", Instant.parse("2026-07-29T18:30:00Z"), 1L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                    .claim("scope", "ticket:triage").claim("support_teams", List.of("TEAM-HOUSING"))))
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"categoryId":"%s","priority":"LOW","supportQueueId":"%s","reason":"No subcategory needed here."}
                    """.formatted(CATEGORY_ID, QUEUE_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subcategoryId").doesNotExist());
    }

    @Test
    void shouldExtractExpectedVersionFromAnUnquotedIfMatchHeader() throws Exception {
        when(triageTicketUseCase.triage(any())).thenReturn(new TriageTicketResult(
            TicketId.of(TICKET_ID), TicketStatus.TRIAGED, TicketCategoryId.of(CATEGORY_ID), TicketSubcategoryId.of(SUBCATEGORY_ID),
            TicketPriority.HIGH, SupportQueueId.of(QUEUE_ID), "support-100", Instant.parse("2026-07-29T18:30:00Z"), 8L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/triage")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                    .claim("scope", "ticket:triage").claim("support_teams", List.of("TEAM-HOUSING"))))
                .header("If-Match", "7")
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk());

        ArgumentCaptor<TriageTicketCommand> captor = ArgumentCaptor.forClass(TriageTicketCommand.class);
        verify(triageTicketUseCase).triage(captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(7L);
        assertThat(captor.getValue().allowedTeamIds()).containsExactly("TEAM-HOUSING");
        assertThat(captor.getValue().actor().subject()).isEqualTo("support-100");
        assertThat(captor.getValue().actor().actorType()).isEqualTo("IT_SUPPORT");
        assertThat(captor.getValue().reason()).isEqualTo("VPN access failure affects the requester's scheduled shift.");
    }
}
