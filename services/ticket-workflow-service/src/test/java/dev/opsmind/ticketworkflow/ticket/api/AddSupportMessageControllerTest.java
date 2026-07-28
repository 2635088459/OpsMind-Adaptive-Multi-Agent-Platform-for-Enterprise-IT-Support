package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageController;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AddTicketMessageUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicTicketMessageController.class)
@Import({SecurityConfiguration.class, PublicTicketMessageApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class AddSupportMessageControllerTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddTicketMessageUseCase addTicketMessageUseCase;

    @Test
    void shouldReturn201ForPublicSupportMessage() throws Exception {
        when(addTicketMessageUseCase.addMessage(any())).thenReturn(new AddTicketMessageResult(
            TicketMessageId.of(UUID.randomUUID()), TicketId.of(TICKET_ID), TicketMessageType.PUBLIC_SUPPORT_MESSAGE,
            MessageVisibility.PUBLIC, "IT_SUPPORT", "The account has been unlocked. Please try again.",
            Instant.parse("2026-07-25T18:30:00Z"), 0L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:public")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"The account has been unlocked. Please try again.","messageType":"PUBLIC_SUPPORT_MESSAGE"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.messageType").value("PUBLIC_SUPPORT_MESSAGE"))
            .andExpect(jsonPath("$.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.authorType").value("IT_SUPPORT"));
    }

    @Test
    void shouldReturn201ForInternalNote() throws Exception {
        when(addTicketMessageUseCase.addMessage(any())).thenReturn(new AddTicketMessageResult(
            TicketMessageId.of(UUID.randomUUID()), TicketId.of(TICKET_ID), TicketMessageType.INTERNAL_SUPPORT_NOTE,
            MessageVisibility.INTERNAL, "IT_SUPPORT", "Identity verification is still required.",
            Instant.parse("2026-07-25T18:30:00Z"), 0L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:internal")))
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"Identity verification is still required.","messageType":"INTERNAL_SUPPORT_NOTE"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.messageType").value("INTERNAL_SUPPORT_NOTE"))
            .andExpect(jsonPath("$.visibility").value("INTERNAL"));
    }

    @Test
    void shouldPassAllowedApplicationCodesFromSupportQueuesClaimToTheCommand() throws Exception {
        when(addTicketMessageUseCase.addMessage(any())).thenReturn(new AddTicketMessageResult(
            TicketMessageId.of(UUID.randomUUID()), TicketId.of(TICKET_ID), TicketMessageType.PUBLIC_SUPPORT_MESSAGE,
            MessageVisibility.PUBLIC, "IT_SUPPORT", "content", Instant.now(), 0L, false
        ));

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT")
                        .claim("support_queues", java.util.List.of("HOUSING_PORTAL", "VPN")))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:public")))
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"content","messageType":"PUBLIC_SUPPORT_MESSAGE"}
                    """))
            .andExpect(status().isCreated());

        ArgumentCaptor<AddTicketMessageCommand> captor = ArgumentCaptor.forClass(AddTicketMessageCommand.class);
        verify(addTicketMessageUseCase).addMessage(captor.capture());
        assertThat(captor.getValue().allowedApplicationCodes()).containsExactlyInAnyOrder(
            dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode.HOUSING_PORTAL,
            dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode.VPN
        );
    }
}
