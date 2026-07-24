package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketController;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CreateTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicTicketController.class)
@Import({SecurityConfiguration.class, PublicTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class CreateTicketControllerTest {

    private static final String VALID_BODY = """
        {"title":"Cannot sign in to Housing Portal","description":"Duo keeps asking me to enroll again.","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTicketUseCase createTicketUseCase;

    @Test
    void shouldReturn201WithLocationAndETagOnSuccess() throws Exception {
        UUID ticketId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-23T16:30:00Z");
        when(createTicketUseCase.create(any())).thenReturn(new CreateTicketResult(
            TicketId.of(ticketId), TicketDisplayId.of("INC-2048"), TicketStatus.NEW, createdAt, 0L, false
        ));

        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/tickets/" + ticketId))
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.ticketId").value(ticketId.toString()))
            .andExpect(jsonPath("$.displayId").value("INC-2048"))
            .andExpect(jsonPath("$.status").value("NEW"))
            .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void shouldAddIdempotencyReplayedHeaderOnlyWhenReplayed() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(createTicketUseCase.create(any())).thenReturn(new CreateTicketResult(
            TicketId.of(ticketId), TicketDisplayId.of("INC-2049"), TicketStatus.NEW, Instant.now(), 0L, true
        ));

        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"));
    }
}
