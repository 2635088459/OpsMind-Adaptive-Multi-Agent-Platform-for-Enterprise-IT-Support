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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicTicketController.class)
@Import({SecurityConfiguration.class, PublicTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("security")
class CreateTicketSecurityTest {

    private static final String VALID_BODY = """
        {"title":"Cannot sign in to Housing Portal","description":"Duo keeps asking me to enroll again.","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTicketUseCase createTicketUseCase;

    @Test
    void shouldRejectRequestWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/tickets")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectAuthenticatedActorWithoutCreateScope() throws Exception {
        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:read")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldAllowAuthenticatedActorWithCreateScope() throws Exception {
        when(createTicketUseCase.create(any())).thenReturn(new CreateTicketResult(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-3000"), TicketStatus.NEW, Instant.now(), 0L, false
        ));

        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isCreated());
    }
}
