package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketController;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CreateTicketUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
class CreateTicketErrorContractTest {

    private static final String VALID_BODY = """
        {"title":"Cannot sign in to Housing Portal","description":"Duo keeps asking me to enroll again.","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTicketUseCase createTicketUseCase;

    @Test
    void shouldReturnIdempotencyKeyReusedEnvelope() throws Exception {
        when(createTicketUseCase.create(any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"))
            .andExpect(jsonPath("$.error.message").exists())
            .andExpect(jsonPath("$.error.traceId").exists())
            .andExpect(jsonPath("$.error.correlationId").exists());
    }

    @Test
    void shouldReturnRequestInProgressWithRetryAfterHeader() throws Exception {
        when(createTicketUseCase.create(any())).thenThrow(new RequestInProgressException("key-2"));

        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isConflict())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.error.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void shouldReturnInternalErrorEnvelopeWithoutExposingExceptionDetails() throws Exception {
        when(createTicketUseCase.create(any())).thenThrow(new RuntimeException("boom: password=hunter2 at com.internal.Secret"));

        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."));
    }
}
